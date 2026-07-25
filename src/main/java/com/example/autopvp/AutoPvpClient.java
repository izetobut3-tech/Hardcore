package com.example.autopvp;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.SwordItem;
import net.minecraft.registry.Registries;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.util.Arrays;
import java.util.List;

public class AutoPvpClient implements ClientModInitializer {

    // ============ AYARLAR ============
    private static final double SALDIRI_MESAFESI = 3.5;   // bu mesafede otomatik vurmaya baslar
    private static final double TARAMA_MESAFESI = 35.0;   // dusmani bu mesafeye kadar arar (30-40 blok arasi)
    private static final double YURUME_HIZI = 0.25;       // tick basina blok (yaklasik yurume hizi)
    private static final float KACIS_CAN_ESIGI = 8.0f;    // bu canin altinda kacmaya basla
    private static final float TAM_CAN = 20.0f;
    public static boolean AKTIF = true;

    private static final List<String> KILIC_SIRASI = Arrays.asList(
            "minecraft:netherite_sword",
            "minecraft:diamond_sword",
            "minecraft:iron_sword",
            "minecraft:golden_sword",
            "minecraft:stone_sword",
            "minecraft:wooden_sword"
    );

    private boolean kaciyorMu = false;
    private int vurusBeklemesi = 0;
    private int zipEskimeSayaci = 0;
    private int potBeklemesi = 0;

    // J tusu ile ac/kapa
    private static KeyBinding ACKAPA_TUSU;

    @Override
    public void onInitializeClient() {
        ACKAPA_TUSU = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.autopvp.togle",      // ceviri anahtari (dosya olmasa da calisir, varsayilan isim gosterilir)
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_J,          // J tusu
                "category.autopvp"        // tus ayarlari ekraninda gorunecek kategori
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // Tus her basildiginda AKTIF durumunu ters cevir (menude degilken de calisir)
            while (ACKAPA_TUSU.wasPressed()) {
                AKTIF = !AKTIF;
                if (client.player != null) {
                    client.player.sendMessage(
                            net.minecraft.text.Text.literal(AKTIF ? "AutoPvP: ACIK" : "AutoPvP: KAPALI"),
                            true // action bar'da gostersin
                    );
                }
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(this::herTick);
    }

    private void herTick(MinecraftClient client) {
        if (!AKTIF) return;
        if (client.player == null || client.world == null || client.interactionManager == null) return;

        PlayerEntity ben = client.player;
        if (potBeklemesi > 0) potBeklemesi--;

        // ---- CAN DURUMU: kacis moduna gir/cik ----
        if (ben.getHealth() <= KACIS_CAN_ESIGI) {
            kaciyorMu = true;
        } else if (ben.getHealth() >= TAM_CAN) {
            kaciyorMu = false;
        }

        if (kaciyorMu) {
            kacmaModu(client, ben);
            return;
        }

        // ---- DUSMAN ARA (30-40 blok yaricap) ----
        PlayerEntity dusman = enYakinDusman(client, ben);
        if (dusman == null) {
            duz(ben);
            return;
        }

        enIyiKiliciKusan(ben);

        double mesafe = ben.distanceTo(dusman);

        if (mesafe <= SALDIRI_MESAFESI) {
            duz(ben); // yaklastik, durup vur
            saldir(client, ben, dusman);
        } else {
            hedefeYuru(ben, dusman); // menzil disinda -> dogru yonde yuru
        }

        // NOT: kamera/bakis yonu (yaw/pitch) bu blokta hicbir asamada degismiyor.
        // attackEntity dusmana donuk olmani gerektirmez; hareket de dogrudan
        // hiz vektoru ile yapiliyor, yani karakter donmeden ilerleyebiliyor.
    }

    // ============ DUSMAN BULMA (genis alan) ============
    private PlayerEntity enYakinDusman(MinecraftClient client, PlayerEntity ben) {
        Box aramaAlani = ben.getBoundingBox().expand(TARAMA_MESAFESI);
        List<PlayerEntity> yakindakiler = client.world.getEntitiesByClass(
                PlayerEntity.class, aramaAlani,
                p -> p != ben && p.isAlive() && !p.isSpectator()
        );

        PlayerEntity enYakin = null;
        double enYakinMesafeKare = Double.MAX_VALUE;
        for (PlayerEntity p : yakindakiler) {
            double d = ben.squaredDistanceTo(p);
            if (d < enYakinMesafeKare) {
                enYakinMesafeKare = d;
                enYakin = p;
            }
        }
        return enYakin;
    }

    // ============ HEDEFE DOGRU YURUME (kafa donmeden) ============
    private void hedefeYuru(PlayerEntity ben, PlayerEntity hedef) {
        double dx = hedef.getX() - ben.getX();
        double dz = hedef.getZ() - ben.getZ();
        double yatayMesafe = Math.sqrt(dx * dx + dz * dz);
        if (yatayMesafe < 0.1) return;

        double vx = (dx / yatayMesafe) * YURUME_HIZI;
        double vz = (dz / yatayMesafe) * YURUME_HIZI;

        Vec3d suankiHiz = ben.getVelocity();
        ben.setVelocity(vx, suankiHiz.y, vz);
        ben.velocityModified = true; // hareketin sunucuya iletilmesi icin gerekli

        // Onunde blok/duvar varsa asmasi icin basit ziplama (kaba bir cozum, gercek
        // pathfinding degil)
        if (ben.horizontalCollision && ben.isOnGround()) {
            ben.jump();
        }
    }

    // Hareketi durdur (yerinde kalsin)
    private void duz(PlayerEntity ben) {
        Vec3d suankiHiz = ben.getVelocity();
        ben.setVelocity(0, suankiHiz.y, 0);
        ben.velocityModified = true;
    }

    // ============ SALDIRI (kafa donmez, ziplayarak kritik vurus) ============
    private void saldir(MinecraftClient client, PlayerEntity ben, PlayerEntity dusman) {
        if (vurusBeklemesi > 0) {
            vurusBeklemesi--;
            return;
        }

        zipEskimeSayaci++;
        if (zipEskimeSayaci % 3 == 0 && ben.isOnGround()) {
            ben.jump();
        }

        client.interactionManager.attackEntity(ben, dusman);
        ben.swingHand(Hand.MAIN_HAND);

        vurusBeklemesi = 4; // ~0.2 saniye vurus araligi
    }

    // ============ KACMA MODU: can iksirini kendi ayagina firlat ============
    private void kacmaModu(MinecraftClient client, PlayerEntity ben) {
        duz(ben); // yerinde kalip iksire odaklan

        if (ben.getHealth() >= TAM_CAN) return; // can tam doldu, gerek yok
        if (potBeklemesi > 0) return;

        int potSlot = -1;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = ben.getInventory().getStack(slot);
            if (stack.getItem() == Items.SPLASH_POTION) { // firlatilan tur
                potSlot = slot;
                break;
            }
        }
        if (potSlot == -1) return; // iksir yok

        int oncekiSlot = ben.getInventory().selectedSlot;
        ben.getInventory().selectedSlot = potSlot;

        // Ayagin altina dusmesi icin bir anlik dosdogru asagi bak, at, hemen eski
        // aciya geri don. Yon (yaw) hic degismiyor, sadece yukari/asagi (pitch)
        // bir tick icin degisip aninda geri donuyor.
        float eskiPitch = ben.getPitch();
        ben.setPitch(-90.0f);

        client.interactionManager.interactItem(ben, Hand.MAIN_HAND);

        ben.setPitch(eskiPitch);
        ben.getInventory().selectedSlot = oncekiSlot;
        potBeklemesi = 30; // atislar arasi bekleme, spam onlemek icin (~1.5 sn)
    }

    // ============ EN IYI KILICI KUSANMA ============
    private void enIyiKiliciKusan(PlayerEntity ben) {
        ItemStack suankiEl = ben.getMainHandStack();
        if (suankiEl.getItem() instanceof SwordItem) return;

        int enIyiIndex = Integer.MAX_VALUE;
        int enIyiSlot = -1;

        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = ben.getInventory().getStack(slot);
            String itemId = Registries.ITEM.getId(stack.getItem()).toString();
            int index = KILIC_SIRASI.indexOf(itemId);
            if (index != -1 && index < enIyiIndex) {
                enIyiIndex = index;
                enIyiSlot = slot;
            }
        }

        if (enIyiSlot != -1) {
            ben.getInventory().selectedSlot = enIyiSlot;
        }
    }
}
