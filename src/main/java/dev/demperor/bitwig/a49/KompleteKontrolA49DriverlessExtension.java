package dev.demperor.bitwig.a49;

import java.lang.reflect.Method;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import com.bitwig.extension.api.util.midi.ShortMidiMessage;
import com.bitwig.extension.callback.ShortMidiMessageReceivedCallback;
import com.bitwig.extension.controller.ControllerExtension;
import com.bitwig.extension.controller.api.Action;
import com.bitwig.extension.controller.api.Application;
import com.bitwig.extension.controller.api.Arpeggiator;
import com.bitwig.extension.controller.api.ControllerHost;
import com.bitwig.extension.controller.api.CursorBrowserResultItem;
import com.bitwig.extension.controller.api.CursorDevice;
import com.bitwig.extension.controller.api.CursorTrack;
import com.bitwig.extension.controller.api.InsertionPoint;
import com.bitwig.extension.controller.api.MidiIn;
import com.bitwig.extension.controller.api.NoteInput;
import com.bitwig.extension.controller.api.Parameter;
import com.bitwig.extension.controller.api.PopupBrowser;
import com.bitwig.extension.controller.api.SettableBeatTimeValue;
import com.bitwig.extension.controller.api.SettableEnumValue;
import com.bitwig.extension.controller.api.SettableIntegerValue;
import com.bitwig.extension.controller.api.Transport;

/**
 * Bitwig Studio controller extension for the Komplete Kontrol A49.
 *
 * <p>The extension uses the A49's MIDI port for notes and the raw HID
 * interface for the transport buttons, browser controls, knobs, LEDs and the
 * 128x32 OLED display. HID events are produced on a background thread and
 * drained on the Bitwig controller thread through a small queue.</p>
 */
public class KompleteKontrolA49DriverlessExtension extends ControllerExtension
{
    /** How often queued HID events are processed on the Bitwig controller thread. */
    private static final int DRAIN_INTERVAL_MS = 5;
    private static final String PREROLL_NONE = "none";
    private static final String PREROLL_1_BAR = "one_bar";

    private static final double LOOP_RESIZE_STEP_BEATS = 1.0;
    private static final double LOOP_MIN_DURATION_BEATS = 1.0;

    /** Values exposed by Bitwig's arpeggiator; used for encoder-style stepping. */
    private static final double[] ARP_RATE_VALUES = new double[] { 0.125, 0.25, 0.5, 1.0, 2.0 };
    private static final double[] ARP_GATE_VALUES = new double[] { 0.25, 0.5, 0.75, 1.0, 1.5, 2.0 };
    private static final String[] ARP_MODES = new String[] {
        "all", "up", "up-down", "up-then-down", "down", "down-up", "down-then-up",
        "flow", "random", "converge-up", "converge-down", "diverge-up", "diverge-down",
        "thumb-up", "thumb-down", "pinky-up", "pinky-down"
    };

    private final ConcurrentLinkedQueue<A49HidEvent> hidEventQueue = new ConcurrentLinkedQueue<A49HidEvent>();
    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    private NoteInput               noteInput;
    private Transport               transport;
    private Application             application;
    private CursorTrack             cursorTrack;
    private CursorDevice            cursorDevice;
    private PopupBrowser            popupBrowser;
    private CursorBrowserResultItem cursorResult;
    private Arpeggiator             arpeggiator;
    private A49HidService           hidService;

    private boolean loopHeld = false;
    private boolean arpEditMode = false;
    private int arpSettingIndex = 0;

    private boolean devicePanelMode = false;
    private int knobCcPageIndex = 0;
    private DeviceNavTarget deviceNavTarget = DeviceNavTarget.DEVICE;
    private ScaleViewMode scaleViewMode = ScaleViewMode.BOTH;

    /** What the encoder points at while device-panel navigation is active. */
    private enum DeviceNavTarget
    {
        DEVICE,
        ADD_BEFORE,
        ADD_AFTER
    }

    /** Which panes are visible in the Arrange view. */
    private enum ScaleViewMode
    {
        BOTH,
        ARRANGER_ONLY,
        CLIP_LAUNCHER_ONLY
    }

    private enum ArpSetting
    {
        ENABLED,
        MODE,
        OCTAVES,
        RATE,
        GATE,
        SHUFFLE,
        OVERLAP
    }

    private static final ArpSetting[] ARP_SETTINGS = new ArpSetting[] {
        ArpSetting.ENABLED,
        ArpSetting.MODE,
        ArpSetting.OCTAVES,
        ArpSetting.RATE,
        ArpSetting.GATE,
        ArpSetting.SHUFFLE,
        ArpSetting.OVERLAP
    };

    protected KompleteKontrolA49DriverlessExtension(
            final KompleteKontrolA49DriverlessExtensionDefinition definition,
            final ControllerHost host)
    {
        super(definition, host);
    }

    @Override
    public void init()
    {
        final ControllerHost host = getHost();
        final MidiIn midiIn = host.getMidiInPort(0);

        this.noteInput = midiIn.createNoteInput(
            "KOMPLETE KONTROL A49",
            "8?????", "9?????", "A?????",
            "C?????", "D?????", "E?????", "F?????");
        this.noteInput.setShouldConsumeEvents(true);

        midiIn.setMidiCallback((ShortMidiMessageReceivedCallback) msg -> onMidi0(msg));
        midiIn.setSysexCallback((String data) -> onSysex0(data));

        this.arpeggiator = this.noteInput.arpeggiator();
        this.arpeggiator.isEnabled().markInterested();
        this.arpeggiator.mode().markInterested();
        this.arpeggiator.octaves().markInterested();
        this.arpeggiator.rate().markInterested();
        this.arpeggiator.gateLength().markInterested();
        this.arpeggiator.shuffle().markInterested();
        this.arpeggiator.enableOverlappingNotes().markInterested();

        this.transport = host.createTransport();
        this.transport.isPlaying().markInterested();
        this.transport.isArrangerRecordEnabled().markInterested();
        this.transport.isArrangerLoopEnabled().markInterested();
        this.transport.arrangerLoopStart().markInterested();
        this.transport.arrangerLoopDuration().markInterested();
        this.transport.isMetronomeEnabled().markInterested();
        this.transport.preRoll().markInterested();
        this.transport.isArrangerAutomationWriteEnabled().markInterested();

        this.application = host.createApplication();
        this.application.panelLayout().markInterested();
        this.cursorTrack = host.createCursorTrack(0, 0);
        this.cursorTrack.name().markInterested();
        this.cursorTrack.mute().markInterested();
        this.cursorTrack.arm().markInterested();
        this.cursorTrack.solo().markInterested();
        this.cursorTrack.hasNext().markInterested();
        this.cursorTrack.hasPrevious().markInterested();
        this.cursorTrack.trackType().markInterested();
        final Parameter volume = this.cursorTrack.volume();
        volume.markInterested();
        volume.displayedValue().markInterested();
        this.cursorTrack.canHoldNoteData().markInterested();
        this.cursorTrack.isGroup().markInterested();
        // isMaster() is not part of the API 25 Track interface, so this is the
        // only track value that still needs a guarded reflective lookup.
        this.markOptionalBooleanInterested("isMaster");

        this.cursorDevice = this.cursorTrack.createCursorDevice();
        this.cursorDevice.exists().markInterested();
        this.cursorDevice.name().markInterested();
        this.cursorDevice.presetName().markInterested();
        this.cursorDevice.isWindowOpen().markInterested();
        this.cursorDevice.hasNext().markInterested();
        this.cursorDevice.hasPrevious().markInterested();

        this.popupBrowser = host.createPopupBrowser();
        this.popupBrowser.exists().markInterested();
        this.cursorResult = (CursorBrowserResultItem) this.popupBrowser.resultsColumn().createCursorItem();
        this.cursorResult.name().markInterested();

        this.installLedObservers();

        this.isRunning.set(true);
        this.hidService = new A49HidService(this.hidEventQueue);
        try
        {
            this.hidService.start();
            this.refreshLatchedLeds();
            this.notifyTrackSelectionLater();
        }
        catch (final Exception ex)
        {
            host.showPopupNotification("A49 HID unavailable: " + ex.getMessage());
        }

        this.scheduleQueueDrain();
    }

    @Override
    public void exit()
    {
        this.isRunning.set(false);
        if (this.hidService != null)
        {
            this.hidService.stop();
            this.hidService = null;
        }
        getHost().println("Komplete Kontrol A49 (driverless) stopped");
    }

    @Override
    public void flush()
    {
    }

    private void installLedObservers()
    {
        this.transport.isPlaying().addValueObserver(value -> this.refreshLatchedLeds());
        this.transport.isArrangerRecordEnabled().addValueObserver(value -> this.refreshLatchedLeds());
        this.transport.isArrangerLoopEnabled().addValueObserver(value -> this.refreshLatchedLeds());
        this.transport.isMetronomeEnabled().addValueObserver(value -> this.refreshLatchedLeds());
        this.arpeggiator.isEnabled().addValueObserver(value -> this.refreshLatchedLeds());
        this.popupBrowser.exists().addValueObserver(value -> this.refreshLatchedLeds());
        this.application.panelLayout().addValueObserver(value -> this.refreshLatchedLeds());
    }

    private void refreshLatchedLeds()
    {
        if (this.hidService == null)
            return;

        final Set<A49Action> latched = EnumSet.noneOf(A49Action.class);

        if (this.transport.isPlaying().get())
            latched.add(A49Action.PLAY);
        if (this.transport.isArrangerRecordEnabled().get())
            latched.add(A49Action.RECORD);
        if (this.transport.isArrangerLoopEnabled().get())
            latched.add(A49Action.LOOP);
        if (this.transport.isMetronomeEnabled().get())
            latched.add(A49Action.METRONOME);
        if (this.arpeggiator.isEnabled().get() || this.arpEditMode)
            latched.add(A49Action.ARP);
        if (this.popupBrowser.exists().get())
            latched.add(A49Action.BROWSER);
        if (this.devicePanelMode)
            latched.add(A49Action.PLUG_IN);

        final String layout = this.application.panelLayout().get();
        if (Application.PANEL_LAYOUT_MIX.equals(layout))
        {
            latched.add(A49Action.IDEAS);
            latched.add(A49Action.TRACK);
        }
        if (Application.PANEL_LAYOUT_EDIT.equals(layout))
            latched.add(A49Action.EDIT_SCALE);

        this.hidService.setLatchedActions(latched);
    }

    private void scheduleQueueDrain()
    {
        if (!this.isRunning.get())
            return;

        getHost().scheduleTask(new Runnable()
        {
            @Override
            public void run()
            {
                drainQueuedHidEvents();
                scheduleQueueDrain();
            }
        }, DRAIN_INTERVAL_MS);
    }

    private void drainQueuedHidEvents()
    {
        A49HidEvent event;
        while ((event = this.hidEventQueue.poll()) != null)
        {
            switch (event.getType())
            {
                case ACTION_PRESSED:
                    this.handlePressed(event.getAction());
                    break;
                case ACTION_RELEASED:
                    this.handleReleased(event.getAction());
                    break;
                case ENCODER_DELTA:
                    this.handleEncoder(event.getEncoderDelta());
                    break;
                default:
                    break;
            }
        }
    }

    private void handlePressed(final A49Action action)
    {
        if (action == A49Action.LOOP)
            this.loopHeld = true;

        this.performDirectAction(action);
        this.injectMappedCc(action, true);
    }

    private void handleReleased(final A49Action action)
    {
        if (action == A49Action.LOOP)
            this.loopHeld = false;

        this.injectMappedCc(action, false);
    }

    private void handleEncoder(final int delta)
    {
        if (delta == 0)
            return;

        if (this.popupBrowser.exists().get())
        {
            this.scrollBrowserResults(delta);
            return;
        }

        if (this.loopHeld)
        {
            this.resizeArrangerLoop(delta);
            return;
        }

        if (this.arpEditMode)
        {
            this.selectArpSetting(delta);
            return;
        }

        if (this.shouldNavigateDevices())
        {
            this.navigateDevices(delta);
            return;
        }

        this.scrollTracks(delta);
    }

    private void injectMappedCc(final A49Action action, final boolean pressed)
    {
        if (action == A49Action.LEFT || action == A49Action.RIGHT)
            return;

        final Integer cc = A49Mappings.DEFAULT_CC_BY_ACTION.get(action);
        if (cc == null)
            return;

        this.noteInput.sendRawMidiEvent(
            A49Mappings.MIDI_STATUS_CC + A49Mappings.MIDI_CHANNEL,
            cc.intValue(),
            pressed ? A49Mappings.MIDI_VALUE_ON : A49Mappings.MIDI_VALUE_OFF);
    }

    private void performDirectAction(final A49Action action)
    {
        switch (action)
        {
            case PLAY:
                this.transport.play();
                break;

            case RESTART:
                this.transport.restart();
                break;

            case STOP:
                this.transport.stop();
                break;

            case RECORD:
                this.transport.record();
                break;

            case COUNT_IN:
                this.toggleCountIn();
                this.notifyLater(() -> "Count-in: " + this.transport.preRoll().get());
                break;

            case LOOP:
                this.transport.isArrangerLoopEnabled().toggle();
                this.notifyLater(() -> "Loop: " + onOff(this.transport.isArrangerLoopEnabled().get()));
                break;

            case METRONOME:
                this.transport.isMetronomeEnabled().toggle();
                this.notifyLater(() -> "Metronome: " + onOff(this.transport.isMetronomeEnabled().get()));
                break;

            case TEMPO:
                this.transport.tapTempo();
                break;

            case AUTO:
                this.transport.toggleWriteArrangerAutomation();
                this.notifyLater(() -> "Automation write: " + onOff(this.transport.isArrangerAutomationWriteEnabled().get()));
                break;

            case UNDO:
                this.application.undo();
                break;

            case REDO:
                this.application.redo();
                break;

            case QUANTIZE:
                this.invokeActionIfPresent("quantize_again");
                this.invokeActionIfPresent("quantize_audio_again");
                break;

            case CLEAR:
                this.application.remove();
                break;

            case LEFT:
                this.switchKnobCcPage(-1);
                break;

            case RIGHT:
                this.switchKnobCcPage(1);
                break;

            case MUTE:
                this.cursorTrack.mute().toggle();
                this.notifyTrackStateLater(() -> "Mute: " + onOff(this.cursorTrack.mute().get()) + " — " + this.safeTrackName());
                break;

            case SOLO:
                this.cursorTrack.solo().toggle();
                this.notifyTrackStateLater(() -> "Solo: " + onOff(this.cursorTrack.solo().get()) + " — " + this.safeTrackName());
                break;

            case TRACK:
                this.application.toggleMixer();
                this.deviceNavTarget = DeviceNavTarget.DEVICE;
                break;

            case IDEAS:
                this.toggleMixView();
                break;

            case PLUG_IN:
                this.toggleDevicePanel();
                break;

            case MIDI:
                this.toggleMidiEditor();
                break;

            case INSTANCE:
                this.toggleSelectedDeviceWindow();
                break;

            case BROWSER:
                this.handleBrowserButton();
                break;

            case BROWSER_KNOB_CLICK:
                this.handleBrowserKnobClick();
                break;

            case PRESET_UP:
                if (this.popupBrowser.exists().get())
                {
                    this.scrollBrowserResults(-1);
                }
                else if (this.arpEditMode)
                {
                    this.changeArpSettingValue(1);
                }
                else
                {
                    this.openPresetBrowser();
                }
                break;

            case PRESET_DOWN:
                if (this.popupBrowser.exists().get())
                {
                    this.scrollBrowserResults(1);
                }
                else if (this.arpEditMode)
                {
                    this.changeArpSettingValue(-1);
                }
                else
                {
                    this.openPresetBrowser();
                }
                break;

            case SCALE:
                this.cycleArrangeAndClipLauncherView();
                break;

            case EDIT_SCALE:
                this.toggleEditView();
                break;

            case ARP:
                this.arpeggiator.isEnabled().toggle();
                this.notifyLater(() -> "ARP: " + onOff(this.arpeggiator.isEnabled().get()));
                break;

            case EDIT_ARP:
                this.arpEditMode = !this.arpEditMode;
                this.refreshLatchedLeds();
                this.notify(this.arpEditMode ? "ARP edit on — " + this.describeCurrentArpSetting() : "ARP edit off");
                break;

            case SHIFT:
                break;

            default:
                getHost().println("A49: unhandled action " + action);
                break;
        }
    }

    private void handleBrowserButton()
    {
        if (this.popupBrowser.exists().get())
        {
            this.popupBrowser.cancel();
            return;
        }

        this.openAddDeviceBrowser();
    }

    private void handleBrowserKnobClick()
    {
        if (this.popupBrowser.exists().get())
        {
            this.popupBrowser.commit();
            this.notifyLater(() -> "Loaded: " + this.safeBrowserItemName());
            return;
        }

        if (this.arpEditMode)
        {
            this.arpEditMode = false;
            this.refreshLatchedLeds();
            this.notify("ARP edit off");
            return;
        }

        if (this.shouldNavigateDevices() && this.deviceNavTarget != DeviceNavTarget.DEVICE)
        {
            this.openAddDeviceBrowser();
            return;
        }

        this.notify("Nothing to commit");
    }

    private void openPresetBrowser()
    {
        if (this.cursorDevice.exists().get())
            getHost().scheduleTask(() -> this.cursorDevice.replaceDeviceInsertionPoint().browse(), 100);
        else
            getHost().scheduleTask(() -> this.cursorTrack.startOfDeviceChainInsertionPoint().browse(), 100);
    }

    private void openAddDeviceBrowser()
    {
        if (!this.cursorDevice.exists().get())
        {
            getHost().scheduleTask(() -> this.cursorTrack.startOfDeviceChainInsertionPoint().browse(), 100);
            this.notify("Add device");
            return;
        }

        final InsertionPoint insertionPoint;
        final String label;
        switch (this.deviceNavTarget)
        {
            case ADD_BEFORE:
                insertionPoint = this.cursorDevice.beforeDeviceInsertionPoint();
                label = "Add device before — " + this.safeDeviceName();
                break;
            case ADD_AFTER:
                insertionPoint = this.cursorDevice.afterDeviceInsertionPoint();
                label = "Add device after — " + this.safeDeviceName();
                break;
            case DEVICE:
            default:
                insertionPoint = this.cursorDevice.afterDeviceInsertionPoint();
                label = "Add device after — " + this.safeDeviceName();
                break;
        }

        getHost().scheduleTask(insertionPoint::browse, 100);
        this.notify(label);
    }

    private void toggleCountIn()
    {
        final SettableEnumValue preRoll = this.transport.preRoll();
        preRoll.set(PREROLL_NONE.equals(preRoll.get()) ? PREROLL_1_BAR : PREROLL_NONE);
    }

    private void toggleMixView()
    {
        final String current = this.application.panelLayout().get();
        if (Application.PANEL_LAYOUT_MIX.equals(current))
        {
            this.application.setPanelLayout(Application.PANEL_LAYOUT_ARRANGE);
            this.scaleViewMode = ScaleViewMode.BOTH;
            this.refreshLatchedLeds();
        }
        else
        {
            this.application.setPanelLayout(Application.PANEL_LAYOUT_MIX);
            this.devicePanelMode = false;
            this.scaleViewMode = ScaleViewMode.BOTH;
            this.refreshLatchedLeds();
        }
    }

    private void toggleDevicePanel()
    {
        this.application.toggleDevices();
        this.devicePanelMode = !this.devicePanelMode;
        this.deviceNavTarget = DeviceNavTarget.DEVICE;
        this.refreshLatchedLeds();
    }

    private void toggleMidiEditor()
    {
        this.application.toggleNoteEditor();
        this.notify("MIDI editor toggled");
    }

    private void cycleArrangeAndClipLauncherView()
    {
        final String currentLayout = this.application.panelLayout().get();
        if (!Application.PANEL_LAYOUT_ARRANGE.equals(currentLayout))
        {
            this.application.setPanelLayout(Application.PANEL_LAYOUT_ARRANGE);
            this.scaleViewMode = ScaleViewMode.BOTH;
            return;
        }

        switch (this.scaleViewMode)
        {
            case BOTH:
                if (this.invokeActionIfPresent("toggle_clip_launcher"))
                {
                    this.scaleViewMode = ScaleViewMode.ARRANGER_ONLY;
                }
                break;

            case ARRANGER_ONLY:
                if (this.invokeActionIfPresent("toggle_clip_launcher")
                    && this.invokeActionIfPresent("toggle_arranger"))
                {
                    this.scaleViewMode = ScaleViewMode.CLIP_LAUNCHER_ONLY;
                }
                break;

            case CLIP_LAUNCHER_ONLY:
            default:
                if (this.invokeActionIfPresent("toggle_arranger"))
                {
                    this.scaleViewMode = ScaleViewMode.BOTH;
                }
                break;
        }
    }

    private void toggleEditView()
    {
        final String current = this.application.panelLayout().get();
        if (Application.PANEL_LAYOUT_EDIT.equals(current))
        {
            this.application.setPanelLayout(Application.PANEL_LAYOUT_ARRANGE);
            this.scaleViewMode = ScaleViewMode.BOTH;
            this.refreshLatchedLeds();
        }
        else
        {
            this.application.setPanelLayout(Application.PANEL_LAYOUT_EDIT);
            this.devicePanelMode = false;
            this.scaleViewMode = ScaleViewMode.BOTH;
            this.refreshLatchedLeds();
        }
    }

    private void toggleSelectedDeviceWindow()
    {
        if (!this.cursorDevice.exists().get())
        {
            this.notify("No device selected");
            return;
        }

        this.cursorDevice.isWindowOpen().toggle();
        this.notifyLater(() -> "Device window: " + onOff(this.cursorDevice.isWindowOpen().get()) + " — " + this.safeDeviceName());
    }

    private void switchKnobCcPage(final int delta)
    {
        this.knobCcPageIndex = Math.floorMod(this.knobCcPageIndex + delta, A49Mappings.KNOB_CC_PAGES.length);
        this.notify(this.describeKnobCcPage());
    }

    private String describeKnobCcPage()
    {
        final int[] page = A49Mappings.KNOB_CC_PAGES[this.knobCcPageIndex];
        return "Knobs Page " + (this.knobCcPageIndex + 1) + " — CC " + page[0] + "-" + page[page.length - 1];
    }

    private int remapKnobCc(final int cc)
    {
        final int knobIndex = this.findKnobIndex(cc);
        if (knobIndex < 0)
            return cc;
        return A49Mappings.KNOB_CC_PAGES[this.knobCcPageIndex][knobIndex];
    }

    private int findKnobIndex(final int cc)
    {
        for (final int[] page : A49Mappings.KNOB_CC_PAGES)
        {
            for (int index = 0; index < page.length; index++)
            {
                if (page[index] == cc)
                    return index;
            }
        }
        return -1;
    }

    private void scrollTracks(final int delta)
    {
        final int steps = Math.abs(delta);
        for (int i = 0; i < steps; i++)
        {
            if (delta > 0)
                this.cursorTrack.selectNext();
            else
                this.cursorTrack.selectPrevious();
        }
        this.notifyTrackSelectionLater();
    }

    private void navigateDevices(final int delta)
    {
        final int steps = Math.abs(delta);
        for (int i = 0; i < steps; i++)
        {
            if (delta > 0)
                this.stepDeviceNavigationForward();
            else
                this.stepDeviceNavigationBackward();
        }
    }

    private void stepDeviceNavigationForward()
    {
        if (!this.cursorDevice.exists().get())
        {
            this.deviceNavTarget = DeviceNavTarget.ADD_AFTER;
            this.notify("Add device");
            return;
        }

        switch (this.deviceNavTarget)
        {
            case DEVICE:
                this.deviceNavTarget = DeviceNavTarget.ADD_AFTER;
                this.notify("Add device after — " + this.safeDeviceName());
                break;

            case ADD_BEFORE:
                this.deviceNavTarget = DeviceNavTarget.DEVICE;
                this.notify("Device: " + this.safeDeviceName());
                break;

            case ADD_AFTER:
                if (this.cursorDevice.hasNext().get())
                {
                    this.cursorDevice.selectNext();
                    this.deviceNavTarget = DeviceNavTarget.DEVICE;
                    this.notifyLater(() -> "Device: " + this.safeDeviceName());
                }
                else
                {
                    this.notify("Add device after — " + this.safeDeviceName());
                }
                break;

            default:
                break;
        }
    }

    private void stepDeviceNavigationBackward()
    {
        if (!this.cursorDevice.exists().get())
        {
            this.deviceNavTarget = DeviceNavTarget.ADD_BEFORE;
            this.notify("Add device");
            return;
        }

        switch (this.deviceNavTarget)
        {
            case DEVICE:
                this.deviceNavTarget = DeviceNavTarget.ADD_BEFORE;
                this.notify("Add device before — " + this.safeDeviceName());
                break;

            case ADD_AFTER:
                this.deviceNavTarget = DeviceNavTarget.DEVICE;
                this.notify("Device: " + this.safeDeviceName());
                break;

            case ADD_BEFORE:
                if (this.cursorDevice.hasPrevious().get())
                {
                    this.cursorDevice.selectPrevious();
                    this.deviceNavTarget = DeviceNavTarget.DEVICE;
                    this.notifyLater(() -> "Device: " + this.safeDeviceName());
                }
                else
                {
                    this.notify("Add device before — " + this.safeDeviceName());
                }
                break;

            default:
                break;
        }
    }

    private boolean shouldNavigateDevices()
    {
        if (!this.devicePanelMode)
            return false;

        final String layout = this.application.panelLayout().get();
        return !Application.PANEL_LAYOUT_MIX.equals(layout);
    }

    private boolean invokeActionIfPresent(final String actionId)
    {
        final Action action = this.application.getAction(actionId);
        if (action == null)
            return false;

        action.invoke();
        return true;
    }

    private void scrollBrowserResults(final int delta)
    {
        final int steps = Math.abs(delta);
        for (int i = 0; i < steps; i++)
        {
            if (delta > 0)
                this.cursorResult.selectNext();
            else
                this.cursorResult.selectPrevious();
        }
        this.notifyLater(() -> "Browser: " + this.safeBrowserItemName());
    }

    private void ensureLoopEnabled()
    {
        if (!this.transport.isArrangerLoopEnabled().get())
            this.transport.isArrangerLoopEnabled().set(true);
    }

    private void resizeArrangerLoop(final int delta)
    {
        this.ensureLoopEnabled();
        final SettableBeatTimeValue duration = this.transport.arrangerLoopDuration();
        final double newDuration = Math.max(LOOP_MIN_DURATION_BEATS, duration.get() + (delta * LOOP_RESIZE_STEP_BEATS));
        duration.set(newDuration);
        this.notifyLoopState("Loop length");
    }

    private void notifyLoopState(final String prefix)
    {
        this.notifyLater(() -> prefix + ": start "
            + formatBeats(this.transport.arrangerLoopStart().get())
            + " / len "
            + formatBeats(this.transport.arrangerLoopDuration().get()));
    }

    private void selectArpSetting(final int delta)
    {
        final int length = ARP_SETTINGS.length;
        final int direction = delta > 0 ? 1 : -1;
        this.arpSettingIndex = (this.arpSettingIndex + direction + length) % length;
        this.notify(this.describeCurrentArpSetting());
    }

    private void changeArpSettingValue(final int direction)
    {
        switch (ARP_SETTINGS[this.arpSettingIndex])
        {
            case ENABLED:
                this.arpeggiator.isEnabled().toggle();
                this.notifyLater(() -> this.describeCurrentArpSetting());
                return;

            case MODE:
                this.arpeggiator.mode().set(this.shiftEnumValue(this.arpeggiator.mode().get(), ARP_MODES, direction));
                break;

            case OCTAVES:
                this.incrementInteger(this.arpeggiator.octaves(), direction, 0, 8);
                break;

            case RATE:
                this.arpeggiator.rate().set(this.shiftDoubleValue(this.arpeggiator.rate().get(), ARP_RATE_VALUES, direction));
                break;

            case GATE:
                this.arpeggiator.gateLength().set(this.shiftDoubleValue(this.arpeggiator.gateLength().get(), ARP_GATE_VALUES, direction));
                break;

            case SHUFFLE:
                this.arpeggiator.shuffle().toggle();
                this.notifyLater(() -> this.describeCurrentArpSetting());
                return;

            case OVERLAP:
                this.arpeggiator.enableOverlappingNotes().toggle();
                this.notifyLater(() -> this.describeCurrentArpSetting());
                return;

            default:
                break;
        }

        this.notifyLater(() -> this.describeCurrentArpSetting());
    }

    private void incrementInteger(final SettableIntegerValue value, final int direction, final int min, final int max)
    {
        final int next = Math.max(min, Math.min(max, value.get() + (direction > 0 ? 1 : -1)));
        value.set(next);
    }

    private String shiftEnumValue(final String current, final String[] values, final int direction)
    {
        int index = 0;
        for (int i = 0; i < values.length; i++)
        {
            if (values[i].equals(current))
            {
                index = i;
                break;
            }
        }

        final int delta = direction > 0 ? 1 : -1;
        return values[(index + delta + values.length) % values.length];
    }

    private double shiftDoubleValue(final double current, final double[] values, final int direction)
    {
        int bestIndex = 0;
        double bestDistance = Math.abs(values[0] - current);
        for (int i = 1; i < values.length; i++)
        {
            final double distance = Math.abs(values[i] - current);
            if (distance < bestDistance)
            {
                bestDistance = distance;
                bestIndex = i;
            }
        }

        final int delta = direction > 0 ? 1 : -1;
        final int index = Math.max(0, Math.min(values.length - 1, bestIndex + delta));
        return values[index];
    }

    private void showTrackFrame()
    {
        if (this.hidService == null)
            return;
        this.hidService.showTrackFrame(this.safeTrackType(), this.safeTrackName(), this.cursorTrack.mute().get(), this.cursorTrack.solo().get(), this.safeTrackVolumeText(), this.cursorTrack.arm().get());
    }

    private void notifyTrackSelection()
    {
        getHost().showPopupNotification(this.describeCurrentTrackSelection());
        this.showTrackFrame();
    }

    private void notifyTrackSelectionLater()
    {
        getHost().scheduleTask(this::notifyTrackSelection, 40);
    }

    private void notifyTrackStateLater(final NotificationSupplier supplier)
    {
        getHost().scheduleTask(() -> {
            getHost().showPopupNotification(supplier.get());
            this.showTrackFrame();
        }, 40);
    }

    private void markOptionalBooleanInterested(final String methodName)
    {
        try
        {
            final Method method = this.cursorTrack.getClass().getMethod(methodName);
            final Object valueObject = method.invoke(this.cursorTrack);
            if (valueObject != null)
            {
                final Method markInterested = valueObject.getClass().getMethod("markInterested");
                markInterested.invoke(valueObject);
            }
        }
        catch (final ReflectiveOperationException ignored)
        {
        }
    }

    private String describeCurrentArpSetting()
    {
        switch (ARP_SETTINGS[this.arpSettingIndex])
        {
            case ENABLED:
                return "ARP enabled: " + onOff(this.arpeggiator.isEnabled().get());
            case MODE:
                return "ARP mode: " + this.arpeggiator.mode().get();
            case OCTAVES:
                return "ARP octaves: " + this.arpeggiator.octaves().get();
            case RATE:
                return "ARP rate: " + trimDouble(this.arpeggiator.rate().get()) + " beats";
            case GATE:
                return "ARP gate: " + trimDouble(this.arpeggiator.gateLength().get());
            case SHUFFLE:
                return "ARP shuffle: " + onOff(this.arpeggiator.shuffle().get());
            case OVERLAP:
                return "ARP overlap: " + onOff(this.arpeggiator.enableOverlappingNotes().get());
            default:
                return "ARP";
        }
    }

    private static String capitalize(final String input)
    {
        if (input == null || input.isEmpty())
            return input;
        return Character.toUpperCase(input.charAt(0)) + input.substring(1);
    }

    private String describeCurrentTrackSelection()
    {
        return capitalize(this.safeTrackType()) + " Track: " + this.safeTrackName();
    }

    private String safeTrackType()
    {
        final String type = this.cursorTrack.trackType().get();
        return type == null || type.trim().isEmpty() ? "ERROR" : type.trim().toLowerCase(Locale.ROOT);
    }

    private String safeTrackVolumeText()
    {
        final Parameter volume = this.cursorTrack.volume();
        if (volume == null)
            return "--";

        final String displayed = volume.displayedValue().get();
        if (displayed != null && !displayed.trim().isEmpty())
            return displayed.trim();
        return Integer.toString((int) Math.round(volume.get() * 100.0)) + "%";
    }

    private String safeTrackName()
    {
        final String name = this.cursorTrack.name().get();
        return name == null || name.trim().isEmpty() ? "(unnamed track)" : name;
    }

    private String safeDeviceName()
    {
        final String name = this.cursorDevice.name().get();
        return name == null || name.trim().isEmpty() ? "(no device selected)" : name;
    }

    private String safeBrowserItemName()
    {
        final String name = this.cursorResult.name().get();
        return name == null || name.trim().isEmpty() ? "(browser item)" : name;
    }

    private static String onOff(final boolean value)
    {
        return value ? "On" : "Off";
    }

    private static String formatBeats(final double beats)
    {
        return trimDouble(beats) + "b";
    }

    private static String trimDouble(final double value)
    {
        if (Math.abs(value - Math.rint(value)) < 0.0001)
            return Long.toString(Math.round(value));
        return String.format(Locale.US, "%.2f", value);
    }

    private void notify(final String text)
    {
        getHost().showPopupNotification(text);
        if (this.hidService != null)
            this.hidService.showText(text);
    }

    private interface NotificationSupplier
    {
        String get();
    }

    private void notifyLater(final NotificationSupplier supplier)
    {
        getHost().scheduleTask(() -> this.notify(supplier.get()), 40);
    }

    private void onMidi0(final ShortMidiMessage msg)
    {
        final int status = msg.getStatusByte();
        final int data1 = msg.getData1();
        final int data2 = msg.getData2();

        if ((status & 0xF0) == A49Mappings.MIDI_STATUS_CC)
        {
            if (data1 == A49Mappings.DEFAULT_CC_BY_ACTION.get(A49Action.LEFT).intValue()
                || data1 == A49Mappings.DEFAULT_CC_BY_ACTION.get(A49Action.RIGHT).intValue())
                return;

            this.noteInput.sendRawMidiEvent(status, this.remapKnobCc(data1), data2);
            return;
        }

        this.noteInput.sendRawMidiEvent(status, data1, data2);
    }

    private void onSysex0(final String data)
    {
        if (data.equals("f07f7f0605f7"))
            this.transport.rewind();
        else if (data.equals("f07f7f0604f7"))
            this.transport.fastForward();
        else if (data.equals("f07f7f0601f7"))
            this.transport.stop();
        else if (data.equals("f07f7f0602f7"))
            this.transport.play();
        else if (data.equals("f07f7f0606f7"))
            this.transport.record();
    }
}
