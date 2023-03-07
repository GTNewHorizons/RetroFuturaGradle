package rfg.examplemod;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import net.minecraft.item.crafting.CraftingManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(modid = "RFGExampleMod", name = "RFG Example mod", version = Tags.VERSION)
public class RfgExampleMod {

    public static final Logger LOGGER = LogManager.getLogger("RFGExampleMod");

    @Mod.EventHandler
    @SuppressWarnings("unused")
    public void init(FMLInitializationEvent ev) {
        LOGGER.info("Hello, RFG example mod!");
        LOGGER.info("Recipe count now: {}", CraftingManager.getInstance().getRecipeList().size());
    }
}
