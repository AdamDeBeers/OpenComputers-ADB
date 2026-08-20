package li.cil.oc.integration.dashpanels;

import li.cil.oc.api.Driver;
import li.cil.oc.integration.ModProxy;
import li.cil.oc.integration.Mods;

public final class ModDashPanels implements ModProxy {
    public static final ModDashPanels INSTANCE = new ModDashPanels();

    private ModDashPanels() {
    }

    @Override
    public li.cil.oc.integration.Mod getMod() {
        return Mods.DashPanels();
    }

    @Override
    public void initialize() {
        Driver.add(DriverDashPanels.Panel.Provider.INSTANCE);
        Driver.add(DriverDashPanels.Panel.INSTANCE);
        Driver.add(DriverDashPanels.WallPanel.Provider.INSTANCE);
        Driver.add(DriverDashPanels.WallPanel.INSTANCE);
        Driver.add(DriverDashPanels.CeilingPanel.Provider.INSTANCE);
        Driver.add(DriverDashPanels.CeilingPanel.INSTANCE);
        Driver.add(DriverDashPanels.Cable.Provider.INSTANCE);
        Driver.add(DriverDashPanels.Cable.INSTANCE);
    }
}
