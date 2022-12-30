package testmod;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(modid = "testmod", version = "1.0", name = "RFG Test Mod", acceptedMinecraftVersions = "[1.7.10]")
public class TestMod {
    private static Logger LOG = LogManager.getLogger("testmod");

    @Mod.EventHandler
    // preInit "Run before anything else. Read your config, create blocks, items,
    // etc, and register them with the GameRegistry."
    public void preInit(FMLPreInitializationEvent event) {
        LOG.info("TestMod preInit");
    }

    @Mod.EventHandler
    // load "Do your mod setup. Build whatever data structures you care about. Register recipes."
    public void init(FMLInitializationEvent event) {
        LOG.info("TestMod init");
    }

    @Mod.EventHandler
    // postInit "Handle interaction with other mods, complete your setup based on this."
    public void postInit(FMLPostInitializationEvent event) {
        LOG.info("TestMod postInit");
    }
}

