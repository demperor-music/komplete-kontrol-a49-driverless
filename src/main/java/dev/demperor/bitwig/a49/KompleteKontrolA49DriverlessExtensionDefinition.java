package dev.demperor.bitwig.a49;

import java.util.UUID;

import com.bitwig.extension.api.PlatformType;
import com.bitwig.extension.controller.AutoDetectionMidiPortNamesList;
import com.bitwig.extension.controller.ControllerExtensionDefinition;
import com.bitwig.extension.controller.api.ControllerHost;

/**
 * Bitwig Studio extension definition for the Komplete Kontrol A49.
 *
 * <p>The MIDI input is used for notes and the keyboard's normal MIDI controls.
 * The display, LEDs and extra buttons are handled through raw HID by
 * {@link KompleteKontrolA49DriverlessExtension}.</p>
 */
public class KompleteKontrolA49DriverlessExtensionDefinition extends ControllerExtensionDefinition
{
    private static final UUID DRIVER_ID = UUID.fromString("4270112c-1d3c-4a9e-aa00-42b1c7714f7a");

    @Override
    public String getName()
    {
        return "Komplete Kontrol A49 (driverless)";
    }

    @Override
    public String getAuthor()
    {
        return "Demperor";
    }

    @Override
    public String getVersion()
    {
        return "0.1.0";
    }

    @Override
    public UUID getId()
    {
        return DRIVER_ID;
    }

    @Override
    public String getHardwareVendor()
    {
        return "Native Instruments";
    }

    @Override
    public String getHardwareModel()
    {
        return "Komplete Kontrol A49 (driverless)";
    }

    @Override
    public int getRequiredAPIVersion()
    {
        return 25;
    }

    @Override
    public int getNumMidiInPorts()
    {
        return 1;
    }

    @Override
    public int getNumMidiOutPorts()
    {
        return 0;
    }

    @Override
    public void listAutoDetectionMidiPortNames(final AutoDetectionMidiPortNamesList list, final PlatformType platformType)
    {
        // The extension has only been tested on Linux. Other platforms can add
        // the controller manually and choose the A49 MIDI input port.
        if (platformType == PlatformType.LINUX)
            list.add(new String[] { "KOMPLETE KONTROL A49 MIDI 1" }, new String[0]);
    }

    @Override
    public KompleteKontrolA49DriverlessExtension createInstance(final ControllerHost host)
    {
        return new KompleteKontrolA49DriverlessExtension(this, host);
    }
}
