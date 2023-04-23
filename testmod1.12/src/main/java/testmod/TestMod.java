package testmod;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.*;
import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * RFG test mod class
 */
@Mod(modid = "testmod", version = Tags.TAG_VERSION, name = "RFG Test Mod", acceptedMinecraftVersions = "[1.12.2]")
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
        Block bed = Blocks.BED;
        LOG.info("Bed name is {}", bed.getRegistryName());
    }
}
