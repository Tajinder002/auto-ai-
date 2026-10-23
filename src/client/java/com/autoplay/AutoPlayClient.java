package com.autoplay;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.DeathScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.io.*;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Random;

/**
 * AutoPlay AI - one self-contained mod.
 *
 * Press K: the bot plays for you. It learns by trial and error (an actor-critic
 * neural network written in plain Java, no extra libraries). Every death ends an
 * episode and triggers a learning update, so it improves the more it dies.
 * Its brain is saved to  config/autoplay-brain.bin  and reloaded next launch.
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

    private static KeyBinding toggleKey;

    private final Brain brain = new Brain();
    private Path savePath;

    private boolean enabled = false;
    private LivingEntity target;
    private double[] prevRaw;
    private boolean deathReported = false;
    private int ticksDead = 0;
    private int episodeTicks = 0;
    private long totalTicks = 0;
    private boolean useTeacher = false;

    @Override
    public void onInitializeClient() {
        savePath = FabricLoader.getInstance().getConfigDir().resolve("autoplay-brain.bin");
        brain.load(savePath);

        KeyBinding.Category category = KeyBinding.Category.create(Identifier.of("autoplay", "main"));
        toggleKey = KeyBindingHelper.registerKeyBinding(
                new KeyBinding("key.autoplay.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_K, category));

        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    // ------------------------------------------------------------------ toggle

    private void setEnabled(MinecraftClient mc, boolean on) {
        enabled = on;
        target = null;
        prevRaw = null;
        deathReported = false;
        episodeTicks = 0;
        brain.discard();
        if (on) {
            say(mc, "AutoPlay ON");
        } else {
            KeyBinding.unpressAll();
            brain.save(savePath);
            say(mc, "AutoPlay OFF (brain saved)");
        }
    }

    private void say(MinecraftClient mc, String msg) {
        if (mc.player != null) mc.player.sendMessage(Text.literal(msg), true);
    }

    // -------------------------------------------------------------------- tick

    private void onTick(MinecraftClient mc) {
        while (toggleKey.wasPressed()) setEnabled(mc, !enabled);
        if (!enabled) return;

        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null) return;

        // ---- dead: report once, then respawn
        if (p.isDead() || mc.currentScreen instanceof DeathScreen) {
            if (!deathReported) onDeath(mc);
            if (ticksDead++ % 20 == 10) p.requestRespawn();
            return;
        }
        deathReported = false;
        if (mc.currentScreen != null) return; // menu/chat open: don't act

        // ---- target tracking (locked until it dies or vanishes)
        int killed = 0;
        if (target != null && (!target.isAlive() || target.isRemoved())) {
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
        applyAction(mc, p, brain.act(x, useTeacher ? teacherAction(raw) : null));
        prevRaw = raw;
        episodeTicks++;
        totalTicks++;

        if (totalTicks % 2000 == 0) brain.save(savePath);
        if (totalTicks % 20 == 0) {
            say(mc, String.format(Locale.ROOT, "AutoPlay | deaths %d | kills %d | best life %ds | this life %ds | teacher %d%%",
                    brain.deaths, brain.kills, brain.bestTicks / 20, episodeTicks / 20,
                    (int) Math.round(brain.teacherProb * 100)));
        }
    }

    private void onDeath(MinecraftClient mc) {
        deathReported = true;
        ticksDead = 0;
        KeyBinding.unpressAll();
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

    // ------------------------------------------------------------- observation

    private double[] observe(ClientPlayerEntity p) {
        double[] o = new double[RAW_N];
        o[HP] = p.getHealth();
        o[FOOD] = p.getHungerManager().getFoodLevel();
        o[DIST] = TARGET_RANGE;
        if (target != null) {
            Vec3d from = p.getEyePos();
            Vec3d d = target.getBoundingBox().getCenter().subtract(from);
            double hor = Math.sqrt(d.x * d.x + d.z * d.z);
            float wantYaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
            float wantPitch = (float) -Math.toDegrees(Math.atan2(d.y, hor));
            o[HAS] = 1;
            o[DIST] = d.length();
            o[YAW] = MathHelper.wrapDegrees(wantYaw - p.getYaw());
            o[PITCH] = wantPitch - p.getPitch();
            o[DY] = d.y;
            o[EHP] = target.getHealth();
        }
        Vec3d v = p.getVelocity();
        o[VX] = v.x;
        o[VY] = v.y;
        o[VZ] = v.z;
        o[GROUND] = p.isOnGround() ? 1 : 0;
        o[CD] = p.getAttackCooldownProgress(0f);
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
    private static int[] teacherAction(double[] r) {
        boolean has = r[HAS] == 1;
        double dist = r[DIST];
        int forward = 0, sprint = 0, attack = 0;
        if (has) {
            forward = dist > 2.5 ? 1 : 0;
            sprint = dist > 5 ? 1 : 0;
            if (dist < 3.3 && Math.abs(r[YAW]) < 15 && Math.abs(r[PITCH]) < 20 && r[CD] > 0.9) attack = 1;
        }
        int yaw = has ? nearest(YAW_BINS, (float) r[YAW]) : 3;
        int pitch = has ? nearest(PITCH_BINS, (float) r[PITCH]) : 2;
        return new int[]{forward, 0, 0, sprint, attack, yaw, pitch};
    }

    private static int nearest(float[] bins, float want) {
        int best = 0;
        for (int i = 1; i < bins.length; i++) {
            if (Math.abs(bins[i] - want) < Math.abs(bins[best] - want)) best = i;
        }
        return best;
    }

    // ----------------------------------------------------------------- actions

    private void applyAction(MinecraftClient mc, ClientPlayerEntity p, int[] a) {
        mc.options.forwardKey.setPressed(a[0] == 1);
        mc.options.backKey.setPressed(a[0] == 2);
        mc.options.leftKey.setPressed(a[1] == 1);
        mc.options.rightKey.setPressed(a[1] == 2);
        mc.options.jumpKey.setPressed(a[2] == 1);
        mc.options.sprintKey.setPressed(a[3] == 1 && a[0] == 1);

        p.setYaw(p.getYaw() + YAW_BINS[a[5]]);
        p.setPitch(MathHelper.clamp(p.getPitch() + PITCH_BINS[a[6]], -90f, 90f));

        if (a[4] == 1) {
            HitResult hit = mc.crosshairTarget;
            if (hit instanceof EntityHitResult ehr && mc.interactionManager != null) {
                mc.interactionManager.attackEntity(p, ehr.getEntity());
                p.swingHand(Hand.MAIN_HAND);
            }
        }
    }

    /** Nearest living entity in range; real players are always preferred over mobs. */
    private LivingEntity pickTarget(MinecraftClient mc, ClientPlayerEntity p) {
        LivingEntity best = null;
        double bestScore = Double.MAX_VALUE;
        for (Entity e : mc.world.getEntities()) {
            if (!(e instanceof LivingEntity) || e == p || e instanceof ArmorStandEntity) continue;
            LivingEntity le = (LivingEntity) e;
            if (!le.isAlive() || le.isSpectator()) continue;
            double d = p.distanceTo(le);
            if (d > TARGET_RANGE) continue;
            double score = d - (le instanceof PlayerEntity ? 1000.0 : 0.0);
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
