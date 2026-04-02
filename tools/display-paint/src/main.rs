//! A49 Display Paint: a small egui tool for painting 128x32 frames and sending
//! them to the Komplete Kontrol A49 over raw HID.
//!
//! This is a development companion, not part of the Bitwig extension runtime.

use std::fmt::Write as _;
use std::path::{Path, PathBuf};
use std::thread;
use std::time::Duration;

use arboard::Clipboard;
use eframe::egui::{self, Color32, Pos2, Rect, Sense, Stroke, Vec2};
use hidapi::HidApi;
use image::{imageops::FilterType, DynamicImage, GrayImage, ImageBuffer, Luma};
use rfd::FileDialog;
use serde::{Deserialize, Serialize};

/// LED frames replayed by the A49 firmware during display initialization.
/// These match the sequence used by the Bitwig extension.
const DISPLAY_STARTUP_LED_SEED: [&str; 10] = [
    "80 7C 00 00 7C 7C 00 7C 7C 7C 7C 7C 7C 00 00 7C 7C 00 00 7C 7C 7C",
    "80 7C 00 00 7C 7C 00 7C 7C 7C 7C 7C 7C 00 00 7C 7C 00 00 7C 7C 7C",
    "80 7C 00 00 00 00 00 7C 00 00 7C 7C 7C 00 00 7C 7C 00 00 00 7C 7C",
    "80 7E 00 00 00 00 00 7C 00 00 7C 7C 7C 00 00 00 00 00 7E 7C 00 00",
    "80 7E 00 00 00 00 00 7C 00 00 7C 7C 7C 00 00 00 00 00 7E 7C 00 00",
    "80 7E 00 00 00 00 00 7C 00 00 7C 7C 7C 00 00 00 00 00 7E 7C 00 00",
    "80 7E 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 7E 7C 00 00",
    "80 7C 00 00 00 00 00 00 00 00 00 00 00 00 00 7C 7C 00 7E 7C 7C 7C",
    "80 7C 00 00 00 00 00 00 00 00 00 00 00 00 00 7C 7C 00 7E 7C 7C 7C",
    "80 7C 00 00 00 00 00 7C 00 00 7C 7C 7C 00 00 7C 7C 00 00 00 7C 7C",
];

fn main() -> eframe::Result<()> {
    let options = eframe::NativeOptions {
        viewport: egui::ViewportBuilder::default()
            .with_inner_size([1500.0, 940.0])
            .with_min_inner_size([1100.0, 720.0])
            .with_title("A49 Display Paint"),
        ..Default::default()
    };

    eframe::run_native(
        "A49 Display Paint",
        options,
        Box::new(|_cc| Ok(Box::new(DisplayPaintApp::default()))),
    )
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum BrushMode {
    Black,
    White,
    Toggle,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum ToolMode {
    Paint,
    Select,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
struct PixelRect {
    x: usize,
    y: usize,
    width: usize,
    height: usize,
}

impl PixelRect {
    fn new_from_points(a: (usize, usize), b: (usize, usize)) -> Self {
        let x0 = a.0.min(b.0);
        let y0 = a.1.min(b.1);
        let x1 = a.0.max(b.0);
        let y1 = a.1.max(b.1);
        Self {
            x: x0,
            y: y0,
            width: x1 - x0 + 1,
            height: y1 - y0 + 1,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
struct EncodingOptions {
    page_height: usize,
    framebuffer_stride: usize,
    canvas_offset: usize,
    black_means_one: bool,
    invert_bits: bool,
    lsb_top: bool,
    reverse_page_order: bool,
}

impl Default for EncodingOptions {
    fn default() -> Self {
        Self {
            page_height: 8,
            framebuffer_stride: 128,
            canvas_offset: 0,
            black_means_one: true,
            invert_bits: false,
            lsb_top: true,
            reverse_page_order: false,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
struct TransportOptions {
    vid_hex: String,
    pid_hex: String,
    prepare_hex: String,
    enable_hex: String,
    shutdown_hex: String,
    header_a_hex: String,
    header_b_hex: String,
    packet_length: usize,
    data_offset: usize,
    data_length: usize,
    pages_per_chunk: usize,
    chunk_a_first_page: usize,
    chunk_b_first_page: usize,
    inter_packet_delay_ms: u64,
    pad_byte_hex: String,
    send_chunk_b: bool,
    auto_send_on_draw: bool,
}

impl Default for TransportOptions {
    fn default() -> Self {
        Self {
            vid_hex: "17CC".into(),
            pid_hex: "1740".into(),
            prepare_hex: "A0 07 00".into(),
            enable_hex: "A0 03 04".into(),
            shutdown_hex: "F8 80 00 20 00 01 00 00 00 64 00".into(),
            header_a_hex: "E0 00 00 00 00 80 00 02 00".into(),
            header_b_hex: "E0 00 00 02 00 80 00 02 00".into(),
            packet_length: 265,
            data_offset: 9,
            data_length: 320,
            pages_per_chunk: 2,
            chunk_a_first_page: 0,
            chunk_b_first_page: 2,
            inter_packet_delay_ms: 40,
            pad_byte_hex: "FF".into(),
            send_chunk_b: true,
            auto_send_on_draw: false,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
struct SaveBundle {
    width: usize,
    height: usize,
    pixels: Vec<u8>,
    encoding: EncodingOptions,
    transport: Option<TransportOptions>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
struct ClipboardSelection {
    kind: String,
    width: usize,
    height: usize,
    pixels: Vec<u8>,
}

struct DisplayPaintApp {
    width: usize,
    height: usize,
    pixels: Vec<bool>, // true = black (1), false = white (0)
    zoom: f32,
    show_grid: bool,
    brush_mode: BrushMode,
    tool_mode: ToolMode,
    threshold: u8,
    hovered_pixel: Option<(usize, usize)>,
    last_file: Option<PathBuf>,
    encoding: EncodingOptions,
    transport: TransportOptions,
    hex_wrap: usize,
    status_message: String,
    selection_anchor: Option<(usize, usize)>,
    selection_rect: Option<PixelRect>,
    packet_preview: String,
    pending_auto_send: bool,
}

impl Default for DisplayPaintApp {
    fn default() -> Self {
        let width = 128;
        let height = 32;
        let mut app = Self {
            width,
            height,
            pixels: vec![true; width * height],
            zoom: 12.0,
            show_grid: true,
            brush_mode: BrushMode::White,
            tool_mode: ToolMode::Paint,
            threshold: 128,
            hovered_pixel: None,
            last_file: None,
            encoding: EncodingOptions::default(),
            transport: TransportOptions::default(),
            hex_wrap: 16,
            status_message: "Ready. Canvas starts all black (1).".into(),
            selection_anchor: None,
            selection_rect: None,
            packet_preview: String::new(),
            pending_auto_send: false,
        };
        app.refresh_packet_preview();
        app
    }
}

impl DisplayPaintApp {
    fn pixel_index(&self, x: usize, y: usize) -> usize {
        y * self.width + x
    }

    fn pixel(&self, x: usize, y: usize) -> bool {
        self.pixels[self.pixel_index(x, y)]
    }

    fn set_pixel(&mut self, x: usize, y: usize, value: bool) {
        let idx = self.pixel_index(x, y);
        self.pixels[idx] = value;
    }

    fn toggle_pixel(&mut self, x: usize, y: usize) {
        let idx = self.pixel_index(x, y);
        self.pixels[idx] = !self.pixels[idx];
    }

    fn clear_selection(&mut self) {
        self.selection_anchor = None;
        self.selection_rect = None;
    }

    fn mark_changed(&mut self) {
        self.refresh_packet_preview();
        if self.transport.auto_send_on_draw {
            self.pending_auto_send = true;
        }
    }

    fn resize_canvas(&mut self, new_width: usize, new_height: usize) {
        let new_width = new_width.clamp(1, 2048);
        let new_height = new_height.clamp(1, 2048);
        let mut next = vec![true; new_width * new_height];
        let copy_w = self.width.min(new_width);
        let copy_h = self.height.min(new_height);
        for y in 0..copy_h {
            for x in 0..copy_w {
                next[y * new_width + x] = self.pixel(x, y);
            }
        }
        self.width = new_width;
        self.height = new_height;
        self.pixels = next;
        self.clear_selection();
        self.refresh_packet_preview();
        self.status_message = format!("Canvas resized to {}×{}.", self.width, self.height);
    }

    fn refresh_packet_preview(&mut self) {
        self.packet_preview = self.current_packet_preview();
    }

    fn crop_to_selection(&mut self) {
        let Some(sel) = self.selection_rect else {
            self.status_message = "No selection to crop.".into();
            return;
        };

        let mut next = vec![true; sel.width * sel.height];
        for y in 0..sel.height {
            for x in 0..sel.width {
                let src_x = sel.x + x;
                let src_y = sel.y + y;
                if src_x < self.width && src_y < self.height {
                    next[y * sel.width + x] = self.pixel(src_x, src_y);
                }
            }
        }

        self.width = sel.width;
        self.height = sel.height;
        self.pixels = next;
        self.clear_selection();
        self.refresh_packet_preview();
        self.status_message = format!(
            "Canvas cropped to selection: {}×{}.",
            self.width, self.height
        );
    }

    fn clear_canvas(&mut self, black: bool) {
        self.pixels.fill(black);
        self.mark_changed();
        self.status_message = if black {
            "Canvas cleared to black (1).".into()
        } else {
            "Canvas cleared to white (0).".into()
        };
    }

    fn invert_canvas(&mut self) {
        for p in &mut self.pixels {
            *p = !*p;
        }
        self.mark_changed();
        self.status_message = "Canvas inverted.".into();
    }

    fn selection_pixels(&self, rect: PixelRect) -> Vec<u8> {
        let mut out = vec![0u8; rect.width * rect.height];
        for y in 0..rect.height {
            for x in 0..rect.width {
                let sx = rect.x + x;
                let sy = rect.y + y;
                if sx < self.width && sy < self.height {
                    out[y * rect.width + x] = u8::from(self.pixel(sx, sy));
                }
            }
        }
        out
    }

    fn apply_pixels_at(
        &mut self,
        px: &[u8],
        src_w: usize,
        src_h: usize,
        dst_x: usize,
        dst_y: usize,
    ) {
        for y in 0..src_h {
            for x in 0..src_w {
                let tx = dst_x + x;
                let ty = dst_y + y;
                if tx < self.width && ty < self.height {
                    let value = px[y * src_w + x] != 0;
                    self.set_pixel(tx, ty, value);
                }
            }
        }
        self.mark_changed();
    }

    fn delete_selection_pixels(&mut self) {
        if let Some(rect) = self.selection_rect {
            for y in rect.y..(rect.y + rect.height).min(self.height) {
                for x in rect.x..(rect.x + rect.width).min(self.width) {
                    self.set_pixel(x, y, true);
                }
            }
            self.mark_changed();
            self.status_message = "Selection cleared to black (1).".into();
        }
    }

    fn copy_selection_to_clipboard(&mut self, cut: bool) {
        let Some(rect) = self.selection_rect else {
            self.status_message = "No selection to copy.".into();
            return;
        };
        let payload = ClipboardSelection {
            kind: "a49-display-selection-v1".into(),
            width: rect.width,
            height: rect.height,
            pixels: self.selection_pixels(rect),
        };
        match serde_json::to_string(&payload) {
            Ok(text) => match Clipboard::new().and_then(|mut cb| cb.set_text(text)) {
                Ok(()) => {
                    if cut {
                        self.delete_selection_pixels();
                        self.status_message =
                            format!("Cut {}×{} selection to clipboard.", rect.width, rect.height);
                    } else {
                        self.status_message = format!(
                            "Copied {}×{} selection to clipboard.",
                            rect.width, rect.height
                        );
                    }
                }
                Err(err) => self.status_message = format!("Clipboard error: {err}"),
            },
            Err(err) => self.status_message = format!("Clipboard serialize error: {err}"),
        }
    }

    fn read_selection_from_clipboard(&self) -> Result<ClipboardSelection, String> {
        let text = Clipboard::new()
            .and_then(|mut cb| cb.get_text())
            .map_err(|e| e.to_string())?;
        if let Ok(sel) = serde_json::from_str::<ClipboardSelection>(&text) {
            if sel.kind == "a49-display-selection-v1" && sel.pixels.len() == sel.width * sel.height
            {
                return Ok(sel);
            }
        }
        if let Ok(bundle) = serde_json::from_str::<SaveBundle>(&text) {
            if bundle.pixels.len() == bundle.width * bundle.height {
                return Ok(ClipboardSelection {
                    kind: "a49-display-selection-v1".into(),
                    width: bundle.width,
                    height: bundle.height,
                    pixels: bundle.pixels,
                });
            }
        }
        Err("Clipboard does not contain an A49 selection/project JSON payload.".into())
    }

    fn paste_from_clipboard(&mut self) {
        match self.read_selection_from_clipboard() {
            Ok(sel) => {
                let (dst_x, dst_y) = self
                    .hovered_pixel
                    .or_else(|| self.selection_rect.map(|r| (r.x, r.y)))
                    .unwrap_or((0, 0));
                self.apply_pixels_at(&sel.pixels, sel.width, sel.height, dst_x, dst_y);
                self.status_message = format!(
                    "Pasted {}×{} selection at {},{}.",
                    sel.width, sel.height, dst_x, dst_y
                );
            }
            Err(err) => self.status_message = err,
        }
    }

    fn copy_text_to_clipboard(&mut self, label: &str, text: String) {
        match Clipboard::new().and_then(|mut cb| cb.set_text(text)) {
            Ok(()) => self.status_message = format!("Copied {label} to clipboard."),
            Err(err) => self.status_message = format!("Clipboard error: {err}"),
        }
    }

    fn load_image_dialog(&mut self) {
        if let Some(path) = FileDialog::new()
            .add_filter(
                "Images",
                &[
                    "png", "jpg", "jpeg", "bmp", "gif", "tif", "tiff", "webp", "ico",
                ],
            )
            .pick_file()
        {
            match self.load_image(&path) {
                Ok(()) => {
                    self.last_file = Some(path.clone());
                    self.status_message = format!("Loaded image: {}", path.display());
                }
                Err(err) => {
                    self.status_message = format!("Failed to load image: {err}");
                }
            }
        }
    }

    fn load_image(&mut self, path: &Path) -> Result<(), String> {
        let img = image::open(path).map_err(|e| e.to_string())?;
        let gray = self.prepare_image(img);
        self.apply_gray_image(&gray);
        self.clear_selection();
        self.refresh_packet_preview();
        Ok(())
    }

    fn prepare_image(&self, img: DynamicImage) -> GrayImage {
        img.resize_exact(self.width as u32, self.height as u32, FilterType::Triangle)
            .to_luma8()
    }

    fn apply_gray_image(&mut self, gray: &GrayImage) {
        for y in 0..self.height {
            for x in 0..self.width {
                let value = gray.get_pixel(x as u32, y as u32)[0];
                let black = value <= self.threshold;
                self.set_pixel(x, y, black);
            }
        }
        self.mark_changed();
    }

    fn save_png_dialog(&mut self) {
        if let Some(path) = FileDialog::new()
            .add_filter("PNG image", &["png"])
            .set_file_name("display.png")
            .save_file()
        {
            match self.save_png(&path) {
                Ok(()) => self.status_message = format!("Saved PNG to {}", path.display()),
                Err(err) => self.status_message = format!("Failed to save PNG: {err}"),
            }
        }
    }

    fn save_png(&self, path: &Path) -> Result<(), String> {
        let mut img: GrayImage = ImageBuffer::new(self.width as u32, self.height as u32);
        for y in 0..self.height {
            for x in 0..self.width {
                let v = if self.pixel(x, y) { 0u8 } else { 255u8 };
                img.put_pixel(x as u32, y as u32, Luma([v]));
            }
        }
        img.save(path).map_err(|e| e.to_string())
    }

    fn save_bundle_dialog(&mut self) {
        if let Some(path) = FileDialog::new()
            .add_filter("JSON", &["json"])
            .set_file_name("display-project.json")
            .save_file()
        {
            let bundle = SaveBundle {
                width: self.width,
                height: self.height,
                pixels: self.pixels.iter().map(|&b| u8::from(b)).collect(),
                encoding: self.encoding.clone(),
                transport: Some(self.transport.clone()),
            };
            match serde_json::to_string_pretty(&bundle) {
                Ok(json) => match std::fs::write(&path, json) {
                    Ok(()) => self.status_message = format!("Saved project to {}", path.display()),
                    Err(err) => self.status_message = format!("Failed to save project: {err}"),
                },
                Err(err) => self.status_message = format!("Failed to serialize project: {err}"),
            }
        }
    }

    fn load_bundle_from_str(&mut self, text: &str, source: &str) -> Result<(), String> {
        let bundle: SaveBundle = serde_json::from_str(text).map_err(|e| e.to_string())?;
        let width = bundle.width.max(1);
        let height = bundle.height.max(1);
        let mut pixels = vec![true; width * height];
        for (dst, src) in pixels.iter_mut().zip(bundle.pixels) {
            *dst = src != 0;
        }
        self.width = width;
        self.height = height;
        self.pixels = pixels;
        self.encoding = bundle.encoding;
        if let Some(transport) = bundle.transport {
            self.transport = transport;
        }
        self.clear_selection();
        self.refresh_packet_preview();
        self.status_message = format!("Loaded project from {source}.");
        Ok(())
    }

    fn load_bundle_dialog(&mut self) {
        if let Some(path) = FileDialog::new().add_filter("JSON", &["json"]).pick_file() {
            match std::fs::read_to_string(&path) {
                Ok(text) => {
                    if let Err(err) = self.load_bundle_from_str(&text, &path.display().to_string())
                    {
                        self.status_message = format!("Invalid project JSON: {err}");
                    }
                }
                Err(err) => self.status_message = format!("Failed to read project: {err}"),
            }
        }
    }

    fn save_bytes_dialog(&mut self, binary: bool) {
        let bytes = self.extracted_bytes();
        let path = if binary {
            FileDialog::new()
                .add_filter("Binary", &["bin"])
                .set_file_name("framebuffer.bin")
                .save_file()
        } else {
            FileDialog::new()
                .add_filter("Text", &["txt"])
                .set_file_name("framebuffer-hex.txt")
                .save_file()
        };

        if let Some(path) = path {
            let result = if binary {
                std::fs::write(&path, bytes)
            } else {
                std::fs::write(&path, self.extracted_hex())
            };
            match result {
                Ok(()) => {
                    self.status_message = format!(
                        "Saved {} to {}",
                        if binary { "binary" } else { "hex" },
                        path.display()
                    )
                }
                Err(err) => self.status_message = format!("Save failed: {err}"),
            }
        }
    }

    fn extracted_bytes(&self) -> Vec<u8> {
        let page_height = self.encoding.page_height.clamp(1, 8);
        let pages = self.height.div_ceil(page_height);
        let stride = self
            .encoding
            .framebuffer_stride
            .max(self.width + self.encoding.canvas_offset);
        let mut out = vec![0u8; pages * stride];

        for out_page in 0..pages {
            let src_page = if self.encoding.reverse_page_order {
                pages - 1 - out_page
            } else {
                out_page
            };
            let y_base = src_page * page_height;

            for x in 0..self.width {
                let dst_x = self.encoding.canvas_offset + x;
                if dst_x >= stride {
                    continue;
                }
                let mut byte = 0u8;
                for row in 0..page_height {
                    let y = y_base + row;
                    let pixel_black = if y < self.height {
                        self.pixel(x, y)
                    } else {
                        false
                    };
                    let mut logical_one = if self.encoding.black_means_one {
                        pixel_black
                    } else {
                        !pixel_black
                    };
                    if self.encoding.invert_bits {
                        logical_one = !logical_one;
                    }
                    if logical_one {
                        let bit = if self.encoding.lsb_top {
                            row
                        } else {
                            page_height - 1 - row
                        };
                        byte |= 1u8 << bit;
                    }
                }
                out[out_page * stride + dst_x] = byte;
            }
        }

        out
    }

    fn extracted_hex(&self) -> String {
        format_hex_lines(&self.extracted_bytes(), self.hex_wrap.max(1))
    }

    fn extracted_rust_array(&self) -> String {
        let bytes = self.extracted_bytes();
        let wrap = self.hex_wrap.max(1);
        let mut out = String::from("[");
        for (i, byte) in bytes.iter().enumerate() {
            if i > 0 {
                out.push_str(", ");
            }
            if i > 0 && i % wrap == 0 {
                out.push('\n');
            }
            let _ = write!(&mut out, "0x{:02X}", byte);
        }
        out.push(']');
        out
    }

    fn parse_vid_pid(&self) -> Result<(u16, u16), String> {
        let vid = parse_hex_u16(&self.transport.vid_hex)?;
        let pid = parse_hex_u16(&self.transport.pid_hex)?;
        Ok((vid, pid))
    }

    fn pad_byte(&self) -> Result<u8, String> {
        parse_hex_u8(&self.transport.pad_byte_hex)
    }

    fn build_packet(&self, header_hex: &str, first_page: usize) -> Result<Vec<u8>, String> {
        let header = parse_hex_bytes(header_hex)?;
        let packet_length = self.transport.packet_length.max(header.len());
        let data_offset = self.transport.data_offset.min(packet_length);
        let total_data_budget = self
            .transport
            .data_length
            .min(packet_length.saturating_sub(data_offset));
        let pad = self.pad_byte()?;
        let mut packet = vec![pad; packet_length];

        let copy_header_len = header.len().min(packet_length);
        packet[..copy_header_len].copy_from_slice(&header[..copy_header_len]);

        let fb = self.extracted_bytes();
        let stride = self
            .encoding
            .framebuffer_stride
            .max(self.width + self.encoding.canvas_offset);
        let page_height = self.encoding.page_height.clamp(1, 8);
        let page_count = self.height.div_ceil(page_height);

        let mut written = 0usize;
        for local_page in 0..self.transport.pages_per_chunk.max(1) {
            let page = first_page + local_page;
            if page >= page_count || written >= total_data_budget {
                break;
            }
            let src_start = page * stride;
            let src_end = (src_start + stride).min(fb.len());
            let available = src_end.saturating_sub(src_start);
            let dst_start = data_offset + written;
            if dst_start >= packet_length {
                break;
            }
            let room = packet_length - dst_start;
            let to_copy = available.min(room).min(total_data_budget - written);
            packet[dst_start..dst_start + to_copy]
                .copy_from_slice(&fb[src_start..src_start + to_copy]);
            written += to_copy;
        }

        Ok(packet)
    }

    fn current_packet_preview(&self) -> String {
        match self.current_packets() {
            Ok((a, b)) => {
                let mut s = String::new();
                s.push_str("Packet A\n");
                s.push_str(&format_hex_lines(&a, self.hex_wrap.max(1)));
                if let Some(b) = b {
                    s.push_str("\n\nPacket B\n");
                    s.push_str(&format_hex_lines(&b, self.hex_wrap.max(1)));
                }
                s
            }
            Err(err) => format!("Packet preview error: {err}"),
        }
    }

    fn current_packets(&self) -> Result<(Vec<u8>, Option<Vec<u8>>), String> {
        let a = self.build_packet(
            &self.transport.header_a_hex,
            self.transport.chunk_a_first_page,
        )?;
        let b = if self.transport.send_chunk_b {
            Some(self.build_packet(
                &self.transport.header_b_hex,
                self.transport.chunk_b_first_page,
            )?)
        } else {
            None
        };
        Ok((a, b))
    }

    fn send_packet_bytes(&self, bytes: &[u8]) -> Result<(), String> {
        let (vid, pid) = self.parse_vid_pid()?;
        let api = HidApi::new().map_err(|e| e.to_string())?;
        let device = api.open(vid, pid).map_err(|e| e.to_string())?;
        device.write(bytes).map_err(|e| e.to_string())?;
        Ok(())
    }

    fn send_hex_command(&self, hex: &str) -> Result<(), String> {
        let bytes = parse_hex_bytes(hex)?;
        self.send_packet_bytes(&bytes)
    }

    fn send_feature_command(&self, hex: &str) -> Result<(), String> {
        let bytes = parse_hex_bytes(hex)?;
        let (vid, pid) = self.parse_vid_pid()?;
        let api = HidApi::new().map_err(|e| e.to_string())?;
        let device = api.open(vid, pid).map_err(|e| e.to_string())?;
        device
            .send_feature_report(&bytes)
            .map_err(|e| e.to_string())?;
        Ok(())
    }

    fn prime_display_feature_reports(&self) -> Result<(), String> {
        let (vid, pid) = self.parse_vid_pid()?;
        let api = HidApi::new().map_err(|e| e.to_string())?;
        let device = api.open(vid, pid).map_err(|e| e.to_string())?;

        let mut f8 = vec![0u8; 11];
        f8[0] = 0xF8;
        device
            .get_feature_report(&mut f8)
            .map_err(|e| e.to_string())?;

        for _ in 0..2 {
            let mut d0 = vec![0u8; 33];
            d0[0] = 0xD0;
            device
                .get_feature_report(&mut d0)
                .map_err(|e| e.to_string())?;
        }
        Ok(())
    }

    fn send_prepare(&mut self) {
        match self.send_hex_command(&self.transport.prepare_hex) {
            Ok(()) => self.status_message = "Sent prepare command.".into(),
            Err(err) => self.status_message = format!("Prepare failed: {err}"),
        }
    }

    fn send_enable(&mut self) {
        match self.send_hex_command(&self.transport.enable_hex) {
            Ok(()) => self.status_message = "Sent enable command.".into(),
            Err(err) => self.status_message = format!("Enable failed: {err}"),
        }
    }

    fn send_shutdown(&mut self) {
        match self.send_feature_command(&self.transport.shutdown_hex) {
            Ok(()) => self.status_message = "Sent shutdown feature report.".into(),
            Err(err) => self.status_message = format!("Shutdown failed: {err}"),
        }
    }

    fn send_current_to_device(&mut self) {
        match self.current_packets() {
            Ok((a, b)) => {
                let result = (|| {
                    self.send_packet_bytes(&a)?;
                    if let Some(b) = b {
                        thread::sleep(Duration::from_millis(self.transport.inter_packet_delay_ms));
                        self.send_packet_bytes(&b)?;
                    }
                    Ok::<(), String>(())
                })();
                match result {
                    Ok(()) => self.status_message = "Sent current image to device.".into(),
                    Err(err) => self.status_message = format!("Send current failed: {err}"),
                }
            }
            Err(err) => self.status_message = format!("Packet build failed: {err}"),
        }
    }

    fn init_and_send_current(&mut self) {
        let result = (|| {
            self.prime_display_feature_reports()?;
            self.send_hex_command(&self.transport.prepare_hex)?;
            thread::sleep(Duration::from_millis(8));
            for seed in DISPLAY_STARTUP_LED_SEED {
                self.send_hex_command(seed)?;
                thread::sleep(Duration::from_millis(8));
            }
            self.send_hex_command(&self.transport.enable_hex)?;
            thread::sleep(Duration::from_millis(120));
            let (a, b) = self.current_packets()?;
            self.send_packet_bytes(&a)?;
            if let Some(b) = b {
                thread::sleep(Duration::from_millis(self.transport.inter_packet_delay_ms));
                self.send_packet_bytes(&b)?;
            }
            Ok::<(), String>(())
        })();
        match result {
            Ok(()) => self.status_message = "Initialized display and sent current image.".into(),
            Err(err) => self.status_message = format!("Init + send failed: {err}"),
        }
    }

    fn cursor_details(&self) -> String {
        let mut parts = Vec::new();
        if let Some((x, y)) = self.hovered_pixel {
            let page_height = self.encoding.page_height.clamp(1, 8);
            let page = y / page_height;
            let bit = y % page_height;
            let stride = self
                .encoding
                .framebuffer_stride
                .max(self.width + self.encoding.canvas_offset);
            let byte_index = page * stride + self.encoding.canvas_offset + x;
            let value = if self.pixel(x, y) { 1 } else { 0 };
            parts.push(format!(
                "Cursor x={} y={} value={} page={} bit={} byteIndex={} stride={}",
                x, y, value, page, bit, byte_index, stride
            ));
        } else {
            parts.push("Cursor: outside canvas".into());
        }
        if let Some(sel) = self.selection_rect {
            parts.push(format!(
                "Selection x={} y={} w={} h={}",
                sel.x, sel.y, sel.width, sel.height
            ));
        }
        parts.join("  |  ")
    }

    fn draw_toolbar(&mut self, ctx: &egui::Context) {
        egui::TopBottomPanel::top("toolbar").show(ctx, |ui| {
            ui.horizontal_wrapped(|ui| {
                if ui.button("Open Image").clicked() {
                    self.load_image_dialog();
                }
                if ui.button("Save PNG").clicked() {
                    self.save_png_dialog();
                }
                if ui.button("Save Project").clicked() {
                    self.save_bundle_dialog();
                }
                if ui.button("Load Project").clicked() {
                    self.load_bundle_dialog();
                }
                ui.separator();
                if ui.button("Clear Black").clicked() {
                    self.clear_canvas(true);
                }
                if ui.button("Clear White").clicked() {
                    self.clear_canvas(false);
                }
                if ui.button("Invert").clicked() {
                    self.invert_canvas();
                }
                ui.separator();
                ui.label("Brush:");
                ui.selectable_value(&mut self.brush_mode, BrushMode::Black, "Black (1)");
                ui.selectable_value(&mut self.brush_mode, BrushMode::White, "White (0)");
                ui.selectable_value(&mut self.brush_mode, BrushMode::Toggle, "Toggle");
                ui.separator();
                ui.label("Tool:");
                ui.selectable_value(&mut self.tool_mode, ToolMode::Paint, "Paint");
                ui.selectable_value(&mut self.tool_mode, ToolMode::Select, "Select");
                ui.separator();
                if ui.button("Copy Selection").clicked() {
                    self.copy_selection_to_clipboard(false);
                }
                if ui.button("Cut Selection").clicked() {
                    self.copy_selection_to_clipboard(true);
                }
                if ui.button("Paste").clicked() {
                    self.paste_from_clipboard();
                }
                if ui.button("Crop to Selection").clicked() {
                    self.crop_to_selection();
                }
                if ui.button("Clear Selection").clicked() {
                    self.clear_selection();
                }
                ui.separator();
                if ui.button("Send Prepare").clicked() {
                    self.send_prepare();
                }
                if ui.button("Send Enable").clicked() {
                    self.send_enable();
                }
                if ui.button("Send Current").clicked() {
                    self.send_current_to_device();
                }
                if ui.button("Init + Send").clicked() {
                    self.init_and_send_current();
                }
                if ui.button("Send Shutdown").clicked() {
                    self.send_shutdown();
                }
                ui.checkbox(
                    &mut self.transport.auto_send_on_draw,
                    "Auto-send when drawing",
                );
            });
        });
    }

    fn draw_side_panel(&mut self, ctx: &egui::Context) {
        egui::SidePanel::left("settings")
            .resizable(true)
            .show(ctx, |ui| {
                ui.heading("Canvas");
                let mut new_w = self.width as u32;
                let mut new_h = self.height as u32;
                ui.horizontal(|ui| {
                    ui.label("Width");
                    ui.add(egui::DragValue::new(&mut new_w).range(1..=2048));
                });
                ui.horizontal(|ui| {
                    ui.label("Height");
                    ui.add(egui::DragValue::new(&mut new_h).range(1..=2048));
                });
                if (new_w as usize != self.width || new_h as usize != self.height)
                    && ui.button("Resize Canvas").clicked()
                {
                    self.resize_canvas(new_w as usize, new_h as usize);
                }

                ui.horizontal(|ui| {
                    ui.label("Threshold");
                    ui.add(egui::Slider::new(&mut self.threshold, 0..=255));
                });
                ui.horizontal(|ui| {
                    ui.label("Zoom");
                    ui.add(egui::Slider::new(&mut self.zoom, 4.0..=40.0));
                });
                ui.checkbox(&mut self.show_grid, "Grid");
                if let Some(path) = &self.last_file {
                    ui.label(format!("Last image: {}", path.display()));
                }

                ui.separator();
                ui.heading("Encoding");
                ui.horizontal(|ui| {
                    ui.label("pageHeight");
                    ui.add(egui::DragValue::new(&mut self.encoding.page_height).range(1..=8));
                });
                ui.horizontal(|ui| {
                    ui.label("framebufferStride");
                    ui.add(
                        egui::DragValue::new(&mut self.encoding.framebuffer_stride).range(1..=4096),
                    );
                });
                ui.horizontal(|ui| {
                    ui.label("canvasOffset");
                    ui.add(egui::DragValue::new(&mut self.encoding.canvas_offset).range(0..=4096));
                });
                ui.checkbox(&mut self.encoding.black_means_one, "blackMeansOne");
                ui.checkbox(&mut self.encoding.invert_bits, "invertBits");
                ui.checkbox(&mut self.encoding.lsb_top, "lsbTop");
                ui.checkbox(&mut self.encoding.reverse_page_order, "reversePageOrder");
                if ui.button("Preset: A49 default").clicked() {
                    self.encoding = EncodingOptions::default();
                    self.status_message = "Applied A49-style encoding preset.".into();
                    self.refresh_packet_preview();
                }

                ui.separator();
                ui.heading("Transport / HID");
                ui.horizontal(|ui| {
                    ui.label("VID");
                    ui.text_edit_singleline(&mut self.transport.vid_hex);
                    ui.label("PID");
                    ui.text_edit_singleline(&mut self.transport.pid_hex);
                });
                labeled_text_edit(ui, "Prepare", &mut self.transport.prepare_hex);
                labeled_text_edit(ui, "Enable", &mut self.transport.enable_hex);
                labeled_text_edit(ui, "Shutdown", &mut self.transport.shutdown_hex);
                labeled_text_edit(ui, "Header A", &mut self.transport.header_a_hex);
                labeled_text_edit(ui, "Header B", &mut self.transport.header_b_hex);
                ui.horizontal(|ui| {
                    ui.label("packetLength");
                    ui.add(egui::DragValue::new(&mut self.transport.packet_length).range(1..=4096));
                });
                ui.horizontal(|ui| {
                    ui.label("dataOffset");
                    ui.add(egui::DragValue::new(&mut self.transport.data_offset).range(0..=4096));
                });
                ui.horizontal(|ui| {
                    ui.label("dataLength");
                    ui.add(egui::DragValue::new(&mut self.transport.data_length).range(0..=4096));
                });
                ui.horizontal(|ui| {
                    ui.label("pagesPerChunk");
                    ui.add(egui::DragValue::new(&mut self.transport.pages_per_chunk).range(1..=16));
                });
                ui.horizontal(|ui| {
                    ui.label("chunkAFirstPage");
                    ui.add(
                        egui::DragValue::new(&mut self.transport.chunk_a_first_page)
                            .range(0..=1024),
                    );
                });
                ui.horizontal(|ui| {
                    ui.label("chunkBFirstPage");
                    ui.add(
                        egui::DragValue::new(&mut self.transport.chunk_b_first_page)
                            .range(0..=1024),
                    );
                });
                ui.horizontal(|ui| {
                    ui.label("interPacketDelayMs");
                    ui.add(
                        egui::DragValue::new(&mut self.transport.inter_packet_delay_ms)
                            .range(0..=5000),
                    );
                });
                ui.horizontal(|ui| {
                    ui.label("padByte");
                    ui.text_edit_singleline(&mut self.transport.pad_byte_hex);
                });
                ui.checkbox(&mut self.transport.send_chunk_b, "sendChunkB");

                if ui.button("Preset: working A49").clicked() {
                    self.encoding = EncodingOptions::default();
                    self.transport = TransportOptions::default();
                    self.width = 128;
                    self.height = 32;
                    self.pixels.resize(self.width * self.height, true);
                    self.refresh_packet_preview();
                    self.status_message = "Applied working A49 transport preset.".into();
                }

                ui.separator();
                ui.heading("Extract");
                ui.horizontal(|ui| {
                    ui.label("Wrap");
                    ui.add(egui::DragValue::new(&mut self.hex_wrap).range(1..=64));
                });
                if ui.button("Copy Hex").clicked() {
                    self.copy_text_to_clipboard("hex", self.extracted_hex());
                }
                if ui.button("Copy Rust Array").clicked() {
                    self.copy_text_to_clipboard("Rust array", self.extracted_rust_array());
                }
                if ui.button("Save Hex").clicked() {
                    self.save_bytes_dialog(false);
                }
                if ui.button("Save Binary").clicked() {
                    self.save_bytes_dialog(true);
                }

                ui.separator();
                let bytes = self.extracted_bytes();
                let pages = self.height.div_ceil(self.encoding.page_height.clamp(1, 8));
                ui.label(format!("Pages: {}", pages));
                ui.label(format!("Extracted bytes: {}", bytes.len()));
                ui.label(format!(
                    "Black pixels: {}",
                    self.pixels.iter().filter(|&&b| b).count()
                ));
                ui.label(format!(
                    "White pixels: {}",
                    self.pixels.iter().filter(|&&b| !b).count()
                ));
            });
    }

    fn draw_status_bar(&mut self, ctx: &egui::Context) {
        egui::TopBottomPanel::bottom("status_bar").show(ctx, |ui| {
            ui.horizontal_wrapped(|ui| {
                ui.label(self.cursor_details());
                ui.separator();
                ui.label(format!("Canvas: {}×{}", self.width, self.height));
                ui.separator();
                ui.label(&self.status_message);
            });
        });
    }

    fn paint_at(&mut self, x: usize, y: usize, primary: bool) -> bool {
        let before = self.pixel(x, y);
        match (self.brush_mode, primary) {
            (BrushMode::Black, true) => self.set_pixel(x, y, true),
            (BrushMode::Black, false) => self.set_pixel(x, y, false),
            (BrushMode::White, true) => self.set_pixel(x, y, false),
            (BrushMode::White, false) => self.set_pixel(x, y, true),
            (BrushMode::Toggle, _) => self.toggle_pixel(x, y),
        }
        self.pixel(x, y) != before
    }

    fn draw_canvas(&mut self, ui: &mut egui::Ui) {
        let size = Vec2::new(
            self.width as f32 * self.zoom,
            self.height as f32 * self.zoom,
        );
        egui::ScrollArea::both().show(ui, |ui| {
            let (rect, response) = ui.allocate_exact_size(size, Sense::click_and_drag());
            let painter = ui.painter_at(rect);
            painter.rect_filled(rect, 0.0, Color32::from_gray(140));

            for y in 0..self.height {
                for x in 0..self.width {
                    let px0 = rect.min.x + x as f32 * self.zoom;
                    let py0 = rect.min.y + y as f32 * self.zoom;
                    let r = Rect::from_min_size(Pos2::new(px0, py0), Vec2::splat(self.zoom));
                    let color = if self.pixel(x, y) {
                        Color32::BLACK
                    } else {
                        Color32::WHITE
                    };
                    painter.rect_filled(r, 0.0, color);
                    if self.show_grid && self.zoom >= 8.0 {
                        painter.rect_stroke(r, 0.0, Stroke::new(1.0_f32, Color32::from_gray(90)));
                    }
                }
            }

            self.hovered_pixel = response.hover_pos().and_then(|pos| {
                let x = ((pos.x - rect.min.x) / self.zoom).floor() as isize;
                let y = ((pos.y - rect.min.y) / self.zoom).floor() as isize;
                if x >= 0 && y >= 0 && (x as usize) < self.width && (y as usize) < self.height {
                    Some((x as usize, y as usize))
                } else {
                    None
                }
            });

            if let Some(sel) = self.selection_rect {
                let min = Pos2::new(
                    rect.min.x + sel.x as f32 * self.zoom,
                    rect.min.y + sel.y as f32 * self.zoom,
                );
                let max = Pos2::new(
                    rect.min.x + (sel.x + sel.width) as f32 * self.zoom,
                    rect.min.y + (sel.y + sel.height) as f32 * self.zoom,
                );
                painter.rect_stroke(
                    Rect::from_min_max(min, max),
                    0.0,
                    Stroke::new(2.0_f32, Color32::from_rgb(0, 200, 255)),
                );
            }

            if let Some((x, y)) = self.hovered_pixel {
                let px0 = rect.min.x + x as f32 * self.zoom;
                let py0 = rect.min.y + y as f32 * self.zoom;
                let r = Rect::from_min_size(Pos2::new(px0, py0), Vec2::splat(self.zoom));
                painter.rect_stroke(r, 0.0, Stroke::new(2.0_f32, Color32::from_rgb(255, 0, 255)));
            }

            match self.tool_mode {
                ToolMode::Paint => {
                    let primary_down = ui.input(|i| i.pointer.primary_down());
                    let secondary_down = ui.input(|i| i.pointer.secondary_down());
                    if (response.dragged() || response.clicked())
                        && (primary_down || secondary_down)
                    {
                        if let Some((x, y)) = self.hovered_pixel {
                            if self.paint_at(x, y, primary_down) {
                                self.mark_changed();
                            }
                        }
                    }
                }
                ToolMode::Select => {
                    if response.drag_started() {
                        if let Some((x, y)) = self.hovered_pixel {
                            self.selection_anchor = Some((x, y));
                            self.selection_rect = Some(PixelRect::new_from_points((x, y), (x, y)));
                        }
                    }
                    if response.dragged() {
                        if let (Some(anchor), Some(current)) =
                            (self.selection_anchor, self.hovered_pixel)
                        {
                            self.selection_rect = Some(PixelRect::new_from_points(anchor, current));
                        }
                    }
                    if response.clicked() {
                        if let Some((x, y)) = self.hovered_pixel {
                            self.selection_anchor = Some((x, y));
                            self.selection_rect = Some(PixelRect::new_from_points((x, y), (x, y)));
                        }
                    }
                    if response.drag_stopped() {
                        self.selection_anchor = None;
                    }
                }
            }
        });
    }

    fn draw_hex_panel(&mut self, ctx: &egui::Context) {
        egui::SidePanel::right("hex_view")
            .resizable(true)
            .default_width(420.0)
            .show(ctx, |ui| {
                ui.heading("Extracted Bytes / Packet Preview");
                ui.label("Live framebuffer bytes and the exact packets that will be sent.");
                ui.separator();

                ui.collapsing("Framebuffer bytes", |ui| {
                    let mut text = self.extracted_hex();
                    ui.add(
                        egui::TextEdit::multiline(&mut text)
                            .font(egui::TextStyle::Monospace)
                            .desired_width(f32::INFINITY)
                            .desired_rows(18)
                            .interactive(false),
                    );
                });

                ui.separator();
                let mut preview = self.packet_preview.clone();
                ui.add(
                    egui::TextEdit::multiline(&mut preview)
                        .font(egui::TextStyle::Monospace)
                        .desired_width(f32::INFINITY)
                        .desired_rows(28)
                        .interactive(false),
                );
            });
    }
}

impl eframe::App for DisplayPaintApp {
    fn update(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
        self.draw_toolbar(ctx);
        self.draw_side_panel(ctx);
        self.draw_hex_panel(ctx);
        self.draw_status_bar(ctx);

        egui::CentralPanel::default().show(ctx, |ui| {
            ui.heading("Paint Canvas");
            ui.label("Paint: left click/drag uses the selected brush, right click/drag uses the opposite. Select: drag to create a rectangle.");
            ui.separator();
            self.draw_canvas(ui);
        });

        if self.pending_auto_send {
            self.pending_auto_send = false;
            self.send_current_to_device();
        }
    }
}

fn labeled_text_edit(ui: &mut egui::Ui, label: &str, value: &mut String) {
    ui.label(label);
    ui.text_edit_multiline(value);
}

fn parse_hex_bytes(input: &str) -> Result<Vec<u8>, String> {
    let cleaned: String = input
        .chars()
        .filter(|c| !c.is_whitespace() && *c != ',' && *c != '[' && *c != ']')
        .collect();
    if cleaned.is_empty() {
        return Ok(Vec::new());
    }
    if !cleaned.len().is_multiple_of(2) {
        return Err("Hex string has odd length".into());
    }
    let mut out = Vec::with_capacity(cleaned.len() / 2);
    let bytes = cleaned.as_bytes();
    let mut i = 0;
    while i < bytes.len() {
        let part = std::str::from_utf8(&bytes[i..i + 2]).map_err(|e| e.to_string())?;
        out.push(u8::from_str_radix(part, 16).map_err(|e| e.to_string())?);
        i += 2;
    }
    Ok(out)
}

fn parse_hex_u16(input: &str) -> Result<u16, String> {
    let cleaned: String = input.chars().filter(|c| !c.is_whitespace()).collect();
    u16::from_str_radix(
        cleaned.trim_start_matches("0x").trim_start_matches("0X"),
        16,
    )
    .map_err(|e| e.to_string())
}

fn parse_hex_u8(input: &str) -> Result<u8, String> {
    let cleaned: String = input.chars().filter(|c| !c.is_whitespace()).collect();
    u8::from_str_radix(
        cleaned.trim_start_matches("0x").trim_start_matches("0X"),
        16,
    )
    .map_err(|e| e.to_string())
}

fn format_hex_lines(bytes: &[u8], wrap: usize) -> String {
    let mut out = String::new();
    for (i, byte) in bytes.iter().enumerate() {
        if i > 0 {
            if i % wrap == 0 {
                out.push('\n');
            } else {
                out.push(' ');
            }
        }
        let _ = write!(&mut out, "{:02X}", byte);
    }
    out
}
