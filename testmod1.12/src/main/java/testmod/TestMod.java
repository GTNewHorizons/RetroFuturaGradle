package testmod;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.*;
import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * RFG test mod class
 */
@Mod(modid = "testmod", version = "TAG_VERSION", name = "RFG Test Mod", acceptedMinecraftVersions = "[1.7.10]")
public class TestMod {
    private static Logger LOG = LogManager.getLogger("testmod");
    public static String replacedVer = "TAG_VERSION";

    @Mod.EventHandler
    // preInit "Run before anything else. Read your config, create blocks, items,
    // etc, and register them with the GameRegistry."
    public void preInit(FMLPreInitializationEvent event) {
        LOG.info("TestMod preInit");
        if (replacedVer.startsWith("TAG_V")) {
            throw new RuntimeException("Wrong substitution");
        }
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
        Block bed = Blocks.bed;
        LOG.info("Bed name is {}", bed.getUnlocalizedName());
    }
}
