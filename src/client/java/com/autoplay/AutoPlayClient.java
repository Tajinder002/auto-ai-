package com.autoplay;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.class_1268;
import net.minecraft.class_1297;
import net.minecraft.class_1309;
import net.minecraft.class_1531;
import net.minecraft.class_1657;
import net.minecraft.class_1661;
import net.minecraft.class_1799;
import net.minecraft.class_1829;
import net.minecraft.class_1743;
import net.minecraft.class_239;
import net.minecraft.class_243;
import net.minecraft.class_2561;
import net.minecraft.class_2960;
import net.minecraft.class_304;
import net.minecraft.class_310;
import net.minecraft.class_3532;
import net.minecraft.class_3675;
import net.minecraft.class_3966;
import net.minecraft.class_418;
import net.minecraft.class_746;
import org.lwjgl.glfw.GLFW;

import java.io.*;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Random;
import java.util.Properties;
import java.util.ArrayList;
import java.util.List;

/**
 * AutoPlay AI - one self-contained mod.
 *
 * Experimental client-side automation for worlds/servers where it is explicitly allowed.
 * K toggles, J toggles training, U shows stats, C cycles presets, X emergency-stops.
 * This is a small policy-learning prototype, not a guaranteed strong PvP agent.
 */
public class AutoPlayClient implements ClientModInitializer {

    // ----------------------------------------------------------- tunables
    private static final double TARGET_RANGE = 32.0;
    private static final int BATCH = 256;               // learn every N ticks (and on every death)
    private static final float[] YAW_BINS = {-40f, -15f, -5f, 0f, 5f, 15f, 40f};
    private static final float[] PITCH_BINS = {-15f, -5f, 0f, 5f, 15f};

    // raw observation indices
    private static final int HP = 0, FOOD = 1, HAS = 2, DIST = 3, YAW = 4, PITCH = 5, DY = 6,
            VX = 7, VY = 8, VZ = 9, GROUND = 10, CD = 11, EHP = 12, RAW_N = 13;

    private static class_304 toggleKey;
    private static class_304 trainingKey;
    private static class_304 statsKey;
    private static class_304 configKey;
    private static class_304 emergencyKey;
    private static class_304 guiKey;

    private final Brain brain = new Brain();
    private Path savePath;
    private Path settingsPath;
    private TrainingStore trainingStore;
    private Path trainingPath;
    private boolean recordManual = false;
    private boolean observePlayers = false;
    private long lastManualSampleTick = 0;
    private long lastObservedSampleTick = 0;
    private long recorderTicks = 0;

    private boolean enabled = false;
    private class_1309 target;
    private double[] prevRaw;
    private boolean deathReported = false;
    private int ticksDead = 0;
    private int episodeTicks = 0;
    private long totalTicks = 0;
    private boolean useTeacher = false;
    private boolean trainingMode = true;
    private boolean emergencyStopped = false;
    private boolean circleStrafe = true;
    private int configPreset = 1; // 0 cautious, 1 balanced, 2 aggressive
    private int statusTicks = 0;
    private int strafePhase = 0;
    private int lastKnownEnemyHealth = 0;

    @Override
    public void onInitializeClient() {
        savePath = FabricLoader.getInstance().getConfigDir().resolve("autoplay-brain.bin");
        settingsPath = FabricLoader.getInstance().getConfigDir().resolve("autoplay-settings.properties");
        trainingPath = FabricLoader.getInstance().getConfigDir().resolve("autoplay-training.csv");
        trainingStore = new TrainingStore(trainingPath);
        brain.load(savePath);
        loadSettings();

        class_304.class_11900 category = class_304.class_11900.method_74698(class_2960.method_60655("autoplay", "main"));
        toggleKey = KeyBindingHelper.registerKeyBinding(
                new class_304("key.autoplay.toggle", class_3675.class_307.field_1668, GLFW.GLFW_KEY_K, category));
        trainingKey = KeyBindingHelper.registerKeyBinding(
                new class_304("key.autoplay.training", class_3675.class_307.field_1668, GLFW.GLFW_KEY_J, category));
        statsKey = KeyBindingHelper.registerKeyBinding(
                new class_304("key.autoplay.stats", class_3675.class_307.field_1668, GLFW.GLFW_KEY_U, category));
        configKey = KeyBindingHelper.registerKeyBinding(
                new class_304("key.autoplay.config", class_3675.class_307.field_1668, GLFW.GLFW_KEY_C, category));
        emergencyKey = KeyBindingHelper.registerKeyBinding(
                new class_304("key.autoplay.emergency", class_3675.class_307.field_1668, GLFW.GLFW_KEY_X, category));
        guiKey = KeyBindingHelper.registerKeyBinding(
                new class_304("key.autoplay.gui", class_3675.class_307.field_1668, GLFW.GLFW_KEY_G, category));

        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    // ------------------------------------------------------------------ toggle

    private void setEnabled(class_310 mc, boolean on) {
        enabled = on;
        target = null;
        prevRaw = null;
        deathReported = false;
        episodeTicks = 0;
        brain.discard();
        if (on) {
            emergencyStopped = false;
            say(mc, "AutoPlay ON");
        } else {
            releaseControls(mc);
            class_304.method_1437();
            brain.save(savePath);
            say(mc, "AutoPlay OFF (brain saved)");
        }
    }

    private void say(class_310 mc, String msg) {
        if (mc.field_1724 != null) mc.field_1724.method_7353(class_2561.method_43470(msg), true);
    }

    void openTrainingGui(class_310 mc) { mc.method_1507(new AutoPlayScreen(this)); }
    boolean isEnabled() { return enabled; }
    boolean isManualRecording() { return recordManual; }
    boolean isObservingPlayers() { return observePlayers; }
    int sampleCount() { return trainingStore == null ? 0 : trainingStore.size(); }
    int replayCount() { return trainingStore == null ? 0 : trainingStore.replayCount(); }
    int kills() { return brain.kills; }
    int deaths() { return brain.deaths; }
    int bestTicks() { return brain.bestTicks; }
    int preset() { return configPreset; }
    String presetName() { return new String[]{"Cautious", "Balanced", "Aggressive"}[configPreset]; }

    void toggleManualRecording(class_310 mc) {
        recordManual = !recordManual;
        say(mc, "Manual learning recording " + (recordManual ? "ON" : "OFF"));
    }
    void togglePlayerObservation(class_310 mc) {
        observePlayers = !observePlayers;
        say(mc, "Visible-player observation " + (observePlayers ? "ON" : "OFF"));
    }
    void toggleAutoplay(class_310 mc) { setEnabled(mc, !enabled); }
    void cyclePresetFromGui(class_310 mc) { cycleConfig(mc); }
    void replayTrain(class_310 mc) {
        if (trainingStore == null) return;
        int learned = 0;
        for (TrainingStore.Sample sample : trainingStore.loadAll()) {
            brain.learnExample(sample.observation, sample.action);
            learned++;
        }
        brain.update(0.0);
        brain.save(savePath);
        say(mc, "Replay training processed " + learned + " examples");
    }
    void clearTrainingData(class_310 mc) {
        if (trainingStore != null && trainingStore.clear()) say(mc, "Training examples cleared");
        else say(mc, "Could not clear training examples");
    }

    private void releaseControls(class_310 mc) {
        if (mc == null || mc.field_1690 == null) return;
        mc.field_1690.field_1894.method_23481(false);
        mc.field_1690.field_1881.method_23481(false);
        mc.field_1690.field_1913.method_23481(false);
        mc.field_1690.field_1849.method_23481(false);
        mc.field_1690.field_1903.method_23481(false);
        mc.field_1690.field_1867.method_23481(false);
    }

    private void showStats(class_310 mc) {
        say(mc, "AutoPlay stats | Kills " + brain.kills + " | Deaths " + brain.deaths
                + " | K/D " + String.format(Locale.ROOT, "%.2f", brain.kills / (double)Math.max(1, brain.deaths))
                + " | Best life " + brain.bestTicks / 20 + "s | Train " + (trainingMode ? "ON" : "OFF"));
        say(mc, "Enemy: " + (target == null ? "none" : String.format(Locale.ROOT, "%.1f blocks, %.1f HP",
                mc.field_1724.method_5739(target), target.method_6032()))
                + " | Preset " + new String[]{"Cautious", "Balanced", "Aggressive"}[configPreset]);
    }

    private void cycleConfig(class_310 mc) {
        configPreset = (configPreset + 1) % 3;
        circleStrafe = configPreset != 0;
        saveSettings();
        say(mc, "AutoPlay preset: " + new String[]{"Cautious", "Balanced", "Aggressive"}[configPreset]
                + " | circle-strafe " + (circleStrafe ? "ON" : "OFF")
                + " | press C again to cycle");
        brain.save(savePath);
    }

    private void loadSettings() {
        if (settingsPath == null || !settingsPath.toFile().exists()) return;
        Properties props = new Properties();
        try (InputStream in = new FileInputStream(settingsPath.toFile())) {
            props.load(in);
            configPreset = Math.max(0, Math.min(2, Integer.parseInt(props.getProperty("preset", "1"))));
            trainingMode = Boolean.parseBoolean(props.getProperty("training", "true"));
            circleStrafe = configPreset != 0;
            brain.teacherProb = Double.parseDouble(props.getProperty("teacherProbability",
                    trainingMode ? "0.55" : "0.15"));
        } catch (Exception ignored) {
            configPreset = 1;
            trainingMode = true;
            circleStrafe = true;
        }
    }

    private void saveSettings() {
        if (settingsPath == null) return;
        Properties props = new Properties();
        props.setProperty("preset", Integer.toString(configPreset));
        props.setProperty("training", Boolean.toString(trainingMode));
        props.setProperty("teacherProbability", Double.toString(brain.teacherProb));
        try (OutputStream out = new FileOutputStream(settingsPath.toFile())) {
            props.store(out, "AutoPlay AI settings");
        } catch (IOException ignored) { }
    }

    private void selectWeapon(class_746 p) {
        // Select the best sword/axe in the hotbar only; never move items or alter inventory contents.
        class_1661 inv = p.method_31548();
        int bestSlot = inv.field_7545;
        int bestScore = -1;
        for (int slot = 0; slot < 9; slot++) {
            class_1799 stack = inv.method_5438(slot);
            if (stack.method_7909() instanceof class_1829) {
                int score = 100;
                if (score > bestScore) { bestScore = score; bestSlot = slot; }
            } else if (stack.method_7909() instanceof class_1743) {
                int score = 90;
                if (score > bestScore) { bestScore = score; bestSlot = slot; }
            }
        }
        if (bestScore >= 0) inv.field_7545 = bestSlot;
    }

    // -------------------------------------------------------------------- tick

    private void onTick(class_310 mc) {
        while (toggleKey.method_1436()) setEnabled(mc, !enabled);
        while (guiKey.method_1436()) openTrainingGui(mc);
        while (trainingKey.method_1436()) {
            trainingMode = !trainingMode;
            brain.teacherProb = trainingMode ? Math.max(brain.teacherProb, 0.55) : Math.min(brain.teacherProb, 0.15);
            say(mc, "AutoPlay training mode " + (trainingMode ? "ON" : "OFF"));
            saveSettings();
            brain.save(savePath);
        }
        while (statsKey.method_1436()) showStats(mc);
        while (configKey.method_1436()) cycleConfig(mc);
        while (emergencyKey.method_1436()) {
            emergencyStopped = true;
            enabled = false;
            releaseControls(mc);
            class_304.method_1437();
            brain.save(savePath);
            say(mc, "AutoPlay EMERGENCY STOP. Press K to re-enable.");
        }
        class_746 p = mc.field_1724;
        if (guiKey != null && mc.field_1755 instanceof AutoPlayScreen) return;
        if (p == null || mc.field_1687 == null) {
            if (enabled) releaseControls(mc);
            return;
        }
        // Manual and observation collection run independently of AutoPlay being enabled.
        if (mc.field_1755 == null && !p.method_29504()) recordTrainingSamples(mc, p);
        if (!enabled) return;
        if (emergencyStopped) emergencyStopped = false;

        // ---- dead: report once, then respawn
        if (p.method_29504() || mc.field_1755 instanceof class_418) {
            releaseControls(mc);
            if (!deathReported) onDeath(mc);
            if (ticksDead++ % 20 == 10) p.method_7331();
            return;
        }
        deathReported = false;
        ticksDead = 0;
        if (mc.field_1755 != null) {
            releaseControls(mc);
            return; // menu/chat open: don't act
        }

        // ---- target tracking (locked until it dies or vanishes)
        int killed = 0;
        if (target != null && (!target.method_5805() || target.method_31481())) {
            killed = 1;
            brain.kills++;
            target = null;
        }
        if (target == null) target = pickTarget(mc, p);

        double[] raw = observe(p);
        double[] x = normalize(raw);

        // ---- reward for the previous action, learn if the batch is full
        if (brain.hasPending() && prevRaw != null) {
            brain.reward(reward(prevRaw, raw, killed), false);
            if (brain.size() >= BATCH) brain.update(brain.value(x));
        }

        // ---- choose and apply a new action
        if (totalTicks % 20 == 0) useTeacher = brain.rnd.nextDouble() < brain.teacherProb;
        brain.teacherProb = Math.max(0.05, brain.teacherProb - 0.00002);
        selectWeapon(p);
        applyAction(mc, p, brain.act(x, useTeacher ? teacherAction(raw, episodeTicks, configPreset) : null));
        prevRaw = raw;
        episodeTicks++;
        totalTicks++;

        if (totalTicks % 1000 == 0) {
            brain.save(savePath);
            saveSettings();
        }
        if (++statusTicks >= 200) {
            statusTicks = 0;
            say(mc, String.format(Locale.ROOT, "AutoPlay | K %d D %d | best life %ds | training %s",
                    brain.kills, brain.deaths, brain.bestTicks / 20, trainingMode ? "ON" : "OFF"));
        }
    }

    private void onDeath(class_310 mc) {
        deathReported = true;
        ticksDead = 0;
        class_304.method_1437();
        if (brain.hasPending()) {
            brain.reward(-5.0, true);
            brain.update(0.0); // learn from this death right now
        }
        brain.deaths++;
        brain.bestTicks = Math.max(brain.bestTicks, episodeTicks);
        episodeTicks = 0;
        target = null;
        prevRaw = null;
        if (brain.deaths % 5 == 0) brain.save(savePath);
    }

    // ------------------------------------------------------------- demonstration capture

    private void recordTrainingSamples(class_310 mc, class_746 p) {
        if (trainingStore == null) return;
        recorderTicks++;
        if (recordManual && recorderTicks - lastManualSampleTick >= 5) {
            class_1309 oldTarget = target;
            target = nearestPlayer(p, mc);
            double[] raw = observe(p);
            if (target != null) {
                int[] action = manualAction(mc, raw);
                trainingStore.append("manual", normalize(raw), action);
                if (trainingStore.size() % 16 == 0) {
                    for (TrainingStore.Sample sample : trainingStore.loadRecent(16)) brain.learnExample(sample.observation, sample.action);
                    brain.update(0.0);
                }
            }
            lastManualSampleTick = recorderTicks;
            target = oldTarget;
        }
        if (observePlayers && recorderTicks - lastObservedSampleTick >= 5) {
            class_1309 actor = nearestOtherPlayer(p, mc);
            if (actor instanceof class_1657 other) {
                class_1309 oldTarget = target;
                target = p;
                double[] raw = observeActor(other, p);
                int[] inferred = inferVisibleAction(other, raw, (int)recorderTicks);
                trainingStore.append("watch", normalize(raw), inferred);
                target = oldTarget;
                if (trainingStore.size() % 16 == 0) {
                    for (TrainingStore.Sample sample : trainingStore.loadRecent(16)) brain.learnExample(sample.observation, sample.action);
                    brain.update(0.0);
                }
            }
            lastObservedSampleTick = recorderTicks;
        }
    }

    private class_1309 nearestPlayer(class_746 p, class_310 mc) {
        class_1309 best = null;
        double bestDist = TARGET_RANGE;
        for (class_1297 entity : mc.field_1687.method_18112()) {
            if (entity instanceof class_1657 player && player != p && player.method_5805()) {
                double d = p.method_5739(player);
                if (d < bestDist) { bestDist = d; best = player; }
            }
        }
        return best;
    }

    private class_1309 nearestOtherPlayer(class_746 p, class_310 mc) {
        return nearestPlayer(p, mc);
    }

    private double[] observeActor(class_1657 actor, class_1309 opponent) {
        double[] o = new double[RAW_N];
        o[HP] = actor.method_6032();
        o[FOOD] = actor.method_7344().method_7586();
        o[DIST] = TARGET_RANGE;
        if (opponent != null) {
            class_243 delta = opponent.method_5829().method_1005().method_1020(actor.method_33571());
            double hor = Math.sqrt(delta.field_1352 * delta.field_1352 + delta.field_1350 * delta.field_1350);
            float wantYaw = (float)Math.toDegrees(Math.atan2(-delta.field_1352, delta.field_1350));
            float wantPitch = (float)-Math.toDegrees(Math.atan2(delta.field_1351, hor));
            o[HAS] = 1; o[DIST] = delta.method_1033();
            o[YAW] = class_3532.method_15393(wantYaw - actor.method_36454());
            o[PITCH] = wantPitch - actor.method_36455(); o[DY] = delta.field_1351;
            o[EHP] = opponent.method_6032();
        }
        class_243 velocity = actor.method_18798();
        o[VX] = velocity.field_1352; o[VY] = velocity.field_1351; o[VZ] = velocity.field_1350;
        o[GROUND] = actor.method_24828() ? 1 : 0;
        o[CD] = actor.method_7261(0f);
        return o;
    }

    private int[] manualAction(class_310 mc, double[] raw) {
        int forward = mc.field_1690.field_1894.method_1434() ? 1 : (mc.field_1690.field_1881.method_1434() ? 2 : 0);
        int strafe = mc.field_1690.field_1913.method_1434() ? 1 : (mc.field_1690.field_1849.method_1434() ? 2 : 0);
        int jump = mc.field_1690.field_1903.method_1434() ? 1 : 0;
        int sprint = mc.field_1690.field_1867.method_1434() ? 1 : 0;
        int attack = mc.field_1690.field_1886.method_1434() ? 1 : 0;
        int yaw = nearest(YAW_BINS, (float)raw[YAW]);
        int pitch = nearest(PITCH_BINS, (float)raw[PITCH]);
        return new int[]{forward, strafe, jump, sprint, attack, yaw, pitch};
    }

    private int[] inferVisibleAction(class_1657 actor, double[] raw, int tick) {
        // Inference only: client can observe position/rotation/velocity, not another player's key presses.
        class_243 velocity = actor.method_18798();
        double speed = Math.sqrt(velocity.field_1352 * velocity.field_1352 + velocity.field_1350 * velocity.field_1350);
        int forward = speed > 0.08 ? 1 : 0;
        int strafe = Math.abs(velocity.field_1352) > Math.abs(velocity.field_1350) ? 1 : 2;
        int jump = !actor.method_24828() ? 1 : 0;
        int sprint = speed > 0.18 ? 1 : 0;
        int attack = actor.method_6055(1.0f) > 0.1f ? 1 : 0;
        return new int[]{forward, strafe, jump, sprint, attack,
                nearest(YAW_BINS, (float)raw[YAW]), nearest(PITCH_BINS, (float)raw[PITCH])};
    }

    // ------------------------------------------------------------- observation

    private double[] observe(class_746 p) {
        double[] o = new double[RAW_N];
        o[HP] = p.method_6032();
        o[FOOD] = p.method_7344().method_7586();
        o[DIST] = TARGET_RANGE;
        if (target != null) {
            class_243 from = p.method_33571();
            class_243 d = target.method_5829().method_1005().method_1020(from);
            double hor = Math.sqrt(d.field_1352 * d.field_1352 + d.field_1350 * d.field_1350);
            float wantYaw = (float) Math.toDegrees(Math.atan2(-d.field_1352, d.field_1350));
            float wantPitch = (float) -Math.toDegrees(Math.atan2(d.field_1351, hor));
            o[HAS] = 1;
            o[DIST] = d.method_1033();
            o[YAW] = class_3532.method_15393(wantYaw - p.method_36454());
            o[PITCH] = wantPitch - p.method_36455();
            o[DY] = d.field_1351;
            o[EHP] = target.method_6032();
        }
        class_243 v = p.method_18798();
        o[VX] = v.field_1352;
        o[VY] = v.field_1351;
        o[VZ] = v.field_1350;
        o[GROUND] = p.method_24828() ? 1 : 0;
        o[CD] = p.method_7261(0f);
        return o;
    }

    private static double[] normalize(double[] r) {
        double[] x = {
                r[HP] / 20, r[FOOD] / 20, r[HAS], r[DIST] / 32, r[YAW] / 180, r[PITCH] / 90, r[DY] / 8,
                r[VX] * 4, r[VY] * 4, r[VZ] * 4, r[GROUND], r[CD], r[EHP] / 20};
        for (int i = 0; i < x.length; i++) x[i] = Math.max(-3, Math.min(3, x[i]));
        return x;
    }

    // ------------------------------------------------------------------ reward

    private static double reward(double[] a, double[] b, int killed) {
        double r = -0.001;                       // tiny time cost: don't idle
        if (killed == 1) r += 5.0;               // kill
        r -= 0.1 * Math.max(0, a[HP] - b[HP]);   // damage taken
        if (killed == 0 && a[HAS] == 1 && b[HAS] == 1) {
            r += 0.15 * Math.max(0, a[EHP] - b[EHP]);               // damage dealt
            double aimA = Math.abs(a[YAW]) / 180 + Math.abs(a[PITCH]) / 90;
            double aimB = Math.abs(b[YAW]) / 180 + Math.abs(b[PITCH]) / 90;
            r += 0.5 * (aimA - aimB);                                // turned toward enemy
            r += 0.05 * (a[DIST] - b[DIST]);                         // got closer
            if (Math.abs(b[YAW]) < 10 && Math.abs(b[PITCH]) < 10 && b[DIST] < 3.5) r += 0.01; // aimed & in reach
        }
        return Math.max(-6, Math.min(6, r));
    }

    // ----------------------------------------------------------------- teacher

    /** Simple hand-written fighter: face the enemy, walk up, hit when in reach. The AI copies it early on. */
    private static int[] teacherAction(double[] r, int ticks, int preset) {
        boolean has = r[HAS] == 1;
        double dist = r[DIST];
        int forward = 0, strafe = 0, jump = 0, sprint = 0, attack = 0;
        if (has) {
            double approach = preset == 0 ? 3.2 : (preset == 2 ? 2.2 : 2.7);
            forward = dist > approach ? 1 : 0;
            sprint = dist > (preset == 0 ? 7 : 5) ? 1 : 0;
            // Alternate strafe directions to avoid running straight into the opponent.
            if (dist < 7 && circleStrafeStatic(ticks, preset)) strafe = ((ticks / 12) % 2 == 0) ? 1 : 2;
            if (dist < 3.2 && Math.abs(r[YAW]) < 15 && Math.abs(r[PITCH]) < 20 && r[CD] > 0.9) attack = 1;
            if (dist < 4.5 && r[GROUND] == 1 && (ticks % (preset == 2 ? 8 : 13) == 0)) jump = 1;
        }
        int yaw = has ? nearest(YAW_BINS, (float) r[YAW]) : 3;
        int pitch = has ? nearest(PITCH_BINS, (float) r[PITCH]) : 2;
        return new int[]{forward, strafe, jump, sprint, attack, yaw, pitch};
    }

    private static boolean circleStrafeStatic(int ticks, int preset) {
        return preset != 0 || ticks % 3 != 0;
    }

    private static int nearest(float[] bins, float want) {
        int best = 0;
        for (int i = 1; i < bins.length; i++) {
            if (Math.abs(bins[i] - want) < Math.abs(bins[best] - want)) best = i;
        }
        return best;
    }

    // ----------------------------------------------------------------- actions

    private void applyAction(class_310 mc, class_746 p, int[] a) {
        mc.field_1690.field_1894.method_23481(a[0] == 1);
        mc.field_1690.field_1881.method_23481(a[0] == 2);
        mc.field_1690.field_1913.method_23481(a[1] == 1);
        mc.field_1690.field_1849.method_23481(a[1] == 2);
        mc.field_1690.field_1903.method_23481(a[2] == 1);
        mc.field_1690.field_1867.method_23481(a[3] == 1 && a[0] == 1);

        p.method_36456(p.method_36454() + YAW_BINS[a[5]]);
        p.method_36457(class_3532.method_15363(p.method_36455() + PITCH_BINS[a[6]], -90f, 90f));

        if (a[4] == 1) {
            class_239 hit = mc.field_1765;
            if (hit instanceof class_3966 ehr && mc.field_1761 != null) {
                mc.field_1761.method_2918(p, ehr.method_17782());
                p.method_6104(class_1268.field_5808);
            }
        }
    }

    /** Nearest living entity in range; real players are always preferred over mobs. */
    private class_1309 pickTarget(class_310 mc, class_746 p) {
        class_1309 best = null;
        double bestScore = Double.MAX_VALUE;
        for (class_1297 e : mc.field_1687.method_18112()) {
            if (!(e instanceof class_1309) || e == p || e instanceof class_1531) continue;
            class_1309 le = (class_1309) e;
            if (!le.method_5805() || le.method_7325()) continue;
            double d = p.method_5739(le);
            if (d > TARGET_RANGE) continue;
            double score = d + (le instanceof class_1657 ? -8.0 : 0.0);
            if (score < bestScore) {
                bestScore = score;
                best = le;
            }
        }
        return best;
    }

    // =====================================================================
    //  The brain: small actor-critic network (13 -> 32 -> 32 -> 24 + value)
    //  Actions are 7 independent choices:
    //  forward/back(3) strafe(3) jump(2) sprint(2) attack(2) yaw(7) pitch(5)
    // =====================================================================
    static final class Brain {
        static final int IN = 13, H = 32, OUT = 24;
        static final int[] HEADS = {3, 3, 2, 2, 2, 7, 5};
        static final double GAMMA = 0.99, LAMBDA = 0.95, LR = 0.002, ENT = 0.01, VC = 0.5, MAX_GRAD = 5.0;

        // parameter layout inside one flat array
        static final int O_W1 = 0;
        static final int O_B1 = O_W1 + H * IN;
        static final int O_W2 = O_B1 + H;
        static final int O_B2 = O_W2 + H * H;
        static final int O_WP = O_B2 + H;
        static final int O_BP = O_WP + OUT * H;
        static final int O_WV = O_BP + OUT;
        static final int O_BV = O_WV + H;
        static final int N = O_BV + 1;
        static final int MAGIC = 0xA17B0002;
        static final int CAP = 1024;

        final double[] w = new double[N];
        final double[] g = new double[N];
        final double[] m = new double[N];
        final double[] v = new double[N];
        int adamT = 0;
        final Random rnd = new Random();

        // scratch space for forward/backward
        final double[] h1 = new double[H], h2 = new double[H];
        final double[] logits = new double[OUT], probs = new double[OUT], dl = new double[OUT];
        final double[] dh1 = new double[H], dh2 = new double[H];

        // pending transition (observation + action waiting for its reward)
        double[] pObs;
        int[] pAct;
        double pVal;
        boolean pending = false;

        // experience buffer
        final double[][] bObs = new double[CAP][];
        final int[][] bAct = new int[CAP][];
        final double[] bRew = new double[CAP], bVal = new double[CAP];
        final boolean[] bDone = new boolean[CAP];
        final boolean[] bTeacher = new boolean[CAP];
        boolean pTeacher = false;
        double teacherProb = 0.8; // how often the built-in teacher drives; fades over time
        int n = 0;

        // lifetime stats (saved with the weights)
        int deaths = 0, kills = 0, bestTicks = 0;

        Brain() {
            init(O_W1, H * IN, Math.sqrt(1.0 / IN));
            init(O_W2, H * H, Math.sqrt(1.0 / H));
            init(O_WP, OUT * H, 0.01);
            init(O_WV, H, 0.1);
            // sane starting habits: walk forward, sometimes sprint/attack, keep aim steady
            w[O_BP + 1] += 1.0;   // forward
            w[O_BP + 6] += 1.5;   // jump: mostly don't
            w[O_BP + 9] += 0.5;   // sprint
            w[O_BP + 11] += 0.5;  // attack
            w[O_BP + 15] += 1.5;  // yaw: mostly no turn
            w[O_BP + 21] += 1.5;  // pitch: mostly no turn
        }

        private void init(int off, int len, double scale) {
            for (int i = 0; i < len; i++) w[off + i] = rnd.nextGaussian() * scale;
        }

        boolean hasPending() { return pending; }
        int size() { return n; }
        void discard() { n = 0; pending = false; }

        /** Runs the network on x; fills h1,h2,logits,probs; returns the value estimate. */
        double forward(double[] x) {
            for (int j = 0; j < H; j++) {
                double s = w[O_B1 + j];
                int b = O_W1 + j * IN;
                for (int i = 0; i < IN; i++) s += w[b + i] * x[i];
                h1[j] = Math.tanh(s);
            }
            for (int k = 0; k < H; k++) {
                double s = w[O_B2 + k];
                int b = O_W2 + k * H;
                for (int j = 0; j < H; j++) s += w[b + j] * h1[j];
                h2[k] = Math.tanh(s);
            }
            for (int o = 0; o < OUT; o++) {
                double s = w[O_BP + o];
                int b = O_WP + o * H;
                for (int k = 0; k < H; k++) s += w[b + k] * h2[k];
                logits[o] = s;
            }
            double val = w[O_BV];
            for (int k = 0; k < H; k++) val += w[O_WV + k] * h2[k];

            int hs = 0;
            for (int size : HEADS) {
                double max = -1e30;
                for (int i = 0; i < size; i++) max = Math.max(max, logits[hs + i]);
                double sum = 0;
                for (int i = 0; i < size; i++) {
                    probs[hs + i] = Math.exp(logits[hs + i] - max);
                    sum += probs[hs + i];
                }
                for (int i = 0; i < size; i++) probs[hs + i] /= sum;
                hs += size;
            }
            return val;
        }

        double value(double[] x) { return forward(x); }

        /** Pick an action by sampling the policy, and remember it until its reward arrives. */
        int[] act(double[] x, int[] forced) {
            double val = forward(x);
            int[] a = new int[HEADS.length];
            int hs = 0;
            for (int h = 0; h < HEADS.length; h++) {
                if (forced != null) { a[h] = forced[h]; hs += HEADS[h]; continue; }
                double r = rnd.nextDouble(), c = 0;
                int pick = HEADS[h] - 1;
                for (int i = 0; i < HEADS[h]; i++) {
                    c += probs[hs + i];
                    if (r < c) { pick = i; break; }
                }
                a[h] = pick;
                hs += HEADS[h];
            }
            pTeacher = forced != null;
            pObs = x.clone();
            pAct = a;
            pVal = val;
            pending = true;
            return a;
        }

        /** Attach the reward to the pending action and store the transition. */
        void reward(double r, boolean done) {
            if (!pending || n >= CAP) { pending = false; return; }
            bObs[n] = pObs;
            bAct[n] = pAct;
            bRew[n] = r;
            bVal[n] = pVal;
            bDone[n] = done;
            bTeacher[n] = pTeacher;
            n++;
            pending = false;
        }

        /** One actor-critic update (GAE advantages, Adam) over everything collected so far. */
        void update(double lastValue) {
            if (n < 8) return; // too little to learn from; keep collecting

            double[] adv = new double[n], ret = new double[n];
            double last = 0, nextV = lastValue;
            for (int t = n - 1; t >= 0; t--) {
                double nonTerm = bDone[t] ? 0 : 1;
                double delta = bRew[t] + GAMMA * nextV * nonTerm - bVal[t];
                last = delta + GAMMA * LAMBDA * nonTerm * last;
                adv[t] = last;
                ret[t] = last + bVal[t];
                nextV = bVal[t];
            }
            double mean = 0, var = 0;
            for (double a : adv) mean += a;
            mean /= n;
            for (double a : adv) var += (a - mean) * (a - mean);
            double std = Math.sqrt(var / n) + 1e-8;
            for (int t = 0; t < n; t++) adv[t] = bTeacher[t] ? 1.0 : (adv[t] - mean) / std;

            Arrays.fill(g, 0);
            for (int t = 0; t < n; t++) {
                double[] x = bObs[t];
                int[] a = bAct[t];
                double val = forward(x);

                int hs = 0;
                for (int h = 0; h < HEADS.length; h++) {
                    int sz = HEADS[h];
                    double ent = 0;
                    for (int i = 0; i < sz; i++) ent -= probs[hs + i] * Math.log(probs[hs + i] + 1e-12);
                    for (int i = 0; i < sz; i++) {
                        double p = probs[hs + i];
                        dl[hs + i] = adv[t] * (p - (i == a[h] ? 1 : 0)) + ENT * p * (Math.log(p + 1e-12) + ent);
                    }
                    hs += sz;
                }
                double dv = VC * (val - ret[t]);

                // value + policy heads
                g[O_BV] += dv;
                for (int k = 0; k < H; k++) {
                    g[O_WV + k] += dv * h2[k];
                    dh2[k] = dv * w[O_WV + k];
                }
                for (int o = 0; o < OUT; o++) {
                    double d = dl[o];
                    g[O_BP + o] += d;
                    int b = O_WP + o * H;
                    for (int k = 0; k < H; k++) {
                        g[b + k] += d * h2[k];
                        dh2[k] += d * w[b + k];
                    }
                }
                // hidden layer 2
                Arrays.fill(dh1, 0);
                for (int k = 0; k < H; k++) {
                    double dz = dh2[k] * (1 - h2[k] * h2[k]);
                    g[O_B2 + k] += dz;
                    int b = O_W2 + k * H;
                    for (int j = 0; j < H; j++) {
                        g[b + j] += dz * h1[j];
                        dh1[j] += dz * w[b + j];
                    }
                }
                // hidden layer 1
                for (int j = 0; j < H; j++) {
                    double dz = dh1[j] * (1 - h1[j] * h1[j]);
                    g[O_B1 + j] += dz;
                    int b = O_W1 + j * IN;
                    for (int i = 0; i < IN; i++) g[b + i] += dz * x[i];
                }
            }

            // average, clip, Adam step
            double norm = 0;
            for (int i = 0; i < N; i++) {
                g[i] /= n;
                norm += g[i] * g[i];
            }
            norm = Math.sqrt(norm);
            double scale = norm > MAX_GRAD ? MAX_GRAD / norm : 1.0;
            adamT++;
            double c1 = 1 - Math.pow(0.9, adamT), c2 = 1 - Math.pow(0.999, adamT);
            for (int i = 0; i < N; i++) {
                double gi = g[i] * scale;
                m[i] = 0.9 * m[i] + 0.1 * gi;
                v[i] = 0.999 * v[i] + 0.001 * gi * gi;
                w[i] -= LR * (m[i] / c1) / (Math.sqrt(v[i] / c2) + 1e-8);
            }
            n = 0;
        }

        // Add a supervised demonstration to the existing policy-gradient buffer.
        // The teacher flag gives this example a positive imitation advantage.
        void learnExample(double[] observation, int[] action) {
            if (observation == null || action == null || observation.length != IN || action.length != HEADS.length) return;
            if (n >= CAP) update(0.0);
            bObs[n] = observation.clone();
            bAct[n] = action.clone();
            bRew[n] = 0.0;
            bVal[n] = 0.0;
            bDone[n] = true;
            bTeacher[n] = true;
            n++;
            if (n >= 32) update(0.0);
        }

        // ------------------------------------------------------ save / load
        void save(Path path) {
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(path.toFile())))) {
                out.writeInt(MAGIC);
                out.writeInt(N);
                for (double d : w) out.writeDouble(d);
                out.writeInt(deaths);
                out.writeInt(kills);
                out.writeInt(bestTicks);
                out.writeDouble(teacherProb);
            } catch (IOException ignored) { }
        }

        void load(Path path) {
            File f = path.toFile();
            if (!f.exists()) return;
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f)))) {
                if (in.readInt() != MAGIC || in.readInt() != N) return;
                double[] tmp = new double[N];
                for (int i = 0; i < N; i++) tmp[i] = in.readDouble();
                System.arraycopy(tmp, 0, w, 0, N);
                deaths = in.readInt();
                kills = in.readInt();
                bestTicks = in.readInt();
                teacherProb = in.readDouble();
            } catch (IOException ignored) { }
        }
    }
}
