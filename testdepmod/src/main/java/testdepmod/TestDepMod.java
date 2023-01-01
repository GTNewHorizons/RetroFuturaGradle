package testdepmod;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.*;
import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * RFG test mod class
 */
@Mod(modid = "testdepmod", version = "1.0", name = "RFG Test Dependency Mod", acceptedMinecraftVersions = "[1.7.10]", dependencies = "required-after:testmod")
public class TestDepMod {
    private static Logger LOG = LogManager.getLogger("testdepmod");

    @Mod.EventHandler
    // preInit "Run before anything else. Read your config, create blocks, items,
    // etc, and register them with the GameRegistry."
    public void preInit(FMLPreInitializationEvent event) {
        LOG.info("TestDepMod preInit");
    }

    @Mod.EventHandler
    // load "Do your mod setup. Build whatever data structures you care about. Register recipes."
    public void init(FMLInitializationEvent event) {
        LOG.info("TestDepMod init");
    }

    @Mod.EventHandler
    // postInit "Handle interaction with other mods, complete your setup based on this."
    public void postInit(FMLPostInitializationEvent event) {
        LOG.info("TestDepMod postInit");
        Block pumpkin = Blocks.pumpkin;
        LOG.info("Pumpkin name is {}", pumpkin.getUnlocalizedName());
    }
}
