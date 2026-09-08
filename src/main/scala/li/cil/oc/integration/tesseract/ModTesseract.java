package li.cil.oc.integration.tesseract;

import li.cil.oc.api.Driver;
import li.cil.oc.integration.ModProxy;
import li.cil.oc.integration.Mods;

public final class ModTesseract implements ModProxy {
    public static final ModTesseract INSTANCE = new ModTesseract();

    private ModTesseract() {
    }

    @Override
    public li.cil.oc.integration.Mod getMod() {
        return Mods.Tesseract();
    }

    @Override
    public void initialize() {
        Driver.add(DriverTesseract.Provider.INSTANCE);
        Driver.add(DriverTesseract.INSTANCE);
    }
}
