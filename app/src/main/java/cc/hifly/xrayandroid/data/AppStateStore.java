package cc.hifly.xrayandroid.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import cc.hifly.xrayandroid.model.AppState;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

public class AppStateStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final File stateFile;

    public AppStateStore(File stateFile) {
        this.stateFile = stateFile;
    }

    public synchronized AppState load() {
        if (!stateFile.exists()) {
            return new AppState();
        }

        try (
                FileInputStream inputStream = new FileInputStream(stateFile);
                InputStreamReader inputStreamReader = new InputStreamReader(inputStream, StandardCharsets.UTF_8);
                BufferedReader reader = new BufferedReader(inputStreamReader)
        ) {
            AppState state = GSON.fromJson(reader, AppState.class);
            return state == null ? new AppState() : normalize(state);
        } catch (Exception ignored) {
            return new AppState();
        }
    }

    public synchronized void save(AppState state) throws Exception {
        File parent = stateFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        try (
                FileOutputStream outputStream = new FileOutputStream(stateFile, false);
                OutputStreamWriter outputStreamWriter = new OutputStreamWriter(outputStream, StandardCharsets.UTF_8);
                BufferedWriter writer = new BufferedWriter(outputStreamWriter)
        ) {
            GSON.toJson(normalize(state), writer);
        }
    }

    private AppState normalize(AppState state) {
        if (state.subscriptions == null) {
            state.subscriptions = new java.util.ArrayList<>();
        }
        if (state.nodes == null) {
            state.nodes = new java.util.ArrayList<>();
        }
        return state;
    }
}
