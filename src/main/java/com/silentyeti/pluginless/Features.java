package com.silentyeti.pluginless;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;

final class Features {
    static FeatureSettings settings = new FeatureSettings();

    static String load() {
        var file = FMLPaths.CONFIGDIR.get().resolve(Pluginless.MOD_ID).resolve("features.json");
        var gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        try {
            if (Files.notExists(file)) {
                Files.createDirectories(file.getParent());
                Files.writeString(file, gson.toJson(new FeatureSettings()));
            }
            var loaded = gson.fromJson(Files.readString(file), FeatureSettings.class);
            var problem = loaded == null ? "the file is empty" : loaded.problem();
            if (problem == null) settings = loaded;
            return problem;
        } catch (IOException | JsonParseException e) {
            return e.getMessage();
        }
    }

    static long cost(String command) {
        return Math.round(settings.commandCosts.getOrDefault(command, 0.0) * 100);
    }
}
