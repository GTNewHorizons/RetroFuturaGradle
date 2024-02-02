import java.util.List;
import java.util.Map;

import net.minecraftforge.gradle.GradleStartCommon;

public class GradleStartServer extends GradleStartCommon {

    public static void main(String[] args) throws Throwable {
        (new GradleStartServer()).launch(args);
    }

    @Override
    protected String getTweakClass() {
        return System.getProperty("gradlestart.serverTweaker", "@@TWEAKERSERVER@@");
    }

    @Override
    protected String getBounceClass() {
        return System.getProperty("gradlestart.bouncerServer", "@@BOUNCERSERVER@@");
    }

    @Override
    protected void preLaunch(Map<String, String> argMap, List<String> extras) {}

    @Override
    protected void setDefaultArguments(Map<String, String> argMap) {}
}
