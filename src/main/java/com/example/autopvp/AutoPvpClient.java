package com.example.autopvp;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.PotionContentsComponent;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.potion.Potion;
import net.minecraft.potion.Potions;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public class AutoPvpClient implements ClientModInitializer {

    private static final double SALDIRI_MESAFESI = 3.5;
    private static final double NISAN_MESAFESI = 4.5;
    private static final double TARAMA_MESAFESI = 35.0;
    private static final float TAM_CAN = 20.0f;
    private static final float SAGLIK_ESIGI = 8.0f;
    private static final float DONUS_YUMUSAKLIGI = 8.0f;
    private static final float MAKS_AIM_ACISI = 95.0f; // bu acidan fazla farkli bakiyorsan (ornegin arkani donuyorsan) mudahale etme
    private static final float VURUS_ACISI_TOLERANSI = 15.0f; // kilic sadece bu koni icindeyse vurulur, gercek hitbox gibi
    private static final int BUFF_TEKRAR_BEKLEME = 10; // efekt paketinin gelmesini bekle, spam onlemi
    public static boolean AKTIF = false;

    private static final List<String> KILIC_SIRASI = Arrays.asList(
            "minecraft:netherite_sword",
            "minecraft:diamond_sword",
            "minecraft:iron_sword",
            "minecraft:golden_sword",
            "minecraft:stone_sword",
            "minecraft:wooden_sword"
    );

    private static final List<RegistryEntry<Potion>> SAGLIK_IKSIRLERI = Arrays.asList(
            Potions.HEALING,
            Potions.STRONG_HEALING
    );
    private static final List<RegistryEntry<Potion>> HIZ_IKSIRLERI = Arrays.asList(
            Potions.STRONG_SWIFTNESS,
            Potions.SWIFTNESS
    );
    private static final List<RegistryEntry<Potion>> KUVVET_IKSIRLERI = Arrays.asList(
            Potions.STRONG_STRENGTH,
            Potions.STRENGTH
    );
    private static final int IKSIR_TASIMA_SLOTU = 8;

    private int buffBeklemesi = 0;
    private boolean saglikIksiriAtildiMi = false;
    private long sonKareZamani = 0L;

    private static final KeyBinding.Category AUTOPVP_KATEGORI =
            KeyBinding.Category.create(Identifier.of("autopvp", "main"));

    private static KeyBinding ACKAPA_TUSU;

    @Override
    public void onInitializeClient() {
        ACKAPA_TUSU = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.autopvp.togle",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_J,
                AUTOPVP_KATEGORI
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (ACKAPA_TUSU.wasPressed()) {
                AKTIF = !AKTIF;
                if (client.player != null) {
                    client.player.sendMessage(
                            net.minecraft.text.Text.literal(AKTIF ? "AutoPvP: ACIK" : "AutoPvP: KAPALI"),
                            true
                    );
                }
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(this::herTick);

        HudRenderCallback.EVENT.register((drawContext, tickCounter) -> {
            if (!AKTIF) return;
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null || client.world == null) return;

            PlayerEntity dusman = enYakinDusman(client, client.player);
            if (dusman == null) return;

            double mesafe = client.player.distanceTo(dusman);
            if (mesafe > NISAN_MESAFESI) return;

            long simdi = System.nanoTime();
            double gecenSaniye = (sonKareZamani == 0L) ? 0.05 : (simdi - sonKareZamani) / 1_000_000_000.0;
            sonKareZamani = simdi;

            heKareDon(client.player, dusman, gecenSaniye);
        });
    }

    private void herTick(MinecraftClient client) {
        if (!AKTIF) return;
        if (client.player == null || client.world == null || client.interactionManager == null) return;

        ClientPlayerEntity ben = client.player;

        potIcIhtiyacVarsa(client, ben);

        if (ben.isUsingItem()) return;

        PlayerEntity dusman = enYakinDusman(client, ben);
        if (dusman == null) return;

        double mesafe = ben.distanceTo(dusman);

        enIyiKiliciKusan(ben);

        if (mesafe <= SALDIRI_MESAFESI && bakisAcisindaMi(ben, dusman)) {
            saldir(client, ben, dusman);
        }
    }

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

    private void heKareDon(PlayerEntity ben, PlayerEntity hedef, double gecenSaniye) {
        double dx = hedef.getX() - ben.getX();
        double dy = hedef.getEyeY() - ben.getEyeY();
        double dz = hedef.getZ() - ben.getZ();
        double yatay = Math.sqrt(dx * dx + dz * dz);

        float hedefYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float hedefPitch = (float) -Math.toDegrees(Math.atan2(dy, yatay));

        float suankiYaw = ben.getYaw();
        float suankiPitch = ben.getPitch();

        float farkYaw = MathHelper.wrapDegrees(hedefYaw - suankiYaw);

        if (Math.abs(farkYaw) > MAKS_AIM_ACISI) {
            return;
        }

        float farkPitch = hedefPitch - suankiPitch;

        float yumusaklikOrani = (float) Math.min(1.0, gecenSaniye * DONUS_YUMUSAKLIGI);

        float yeniYaw = suankiYaw + farkYaw * yumusaklikOrani;
        float yeniPitch = suankiPitch + farkPitch * yumusaklikOrani;
        yeniPitch = MathHelper.clamp(yeniPitch, -90.0f, 90.0f);

        ben.setYaw(yeniYaw);
        ben.setPitch(yeniPitch);
        ben.setBodyYaw(yeniYaw);
        ben.setHeadYaw(yeniYaw);
    }

    private boolean bakisAcisindaMi(PlayerEntity ben, PlayerEntity dusman) {
        double dx = dusman.getX() - ben.getX();
        double dz = dusman.getZ() - ben.getZ();
        float hedefYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float fark = MathHelper.wrapDegrees(hedefYaw - ben.getYaw());
        return Math.abs(fark) <= VURUS_ACISI_TOLERANSI;
    }

    private void saldir(MinecraftClient client, PlayerEntity ben, PlayerEntity dusman) {
        if (ben.getAttackCooldownProgress(0.0f) < 1.0f) return;
        client.interactionManager.attackEntity(ben, dusman);
        ben.swingHand(Hand.MAIN_HAND);
    }

    private void potIcIhtiyacVarsa(MinecraftClient client, ClientPlayerEntity ben) {
        if (ben.getHealth() < SAGLIK_ESIGI) {
            if (!saglikIksiriAtildiMi) {
                iksirVarsaAt(client, ben, SAGLIK_IKSIRLERI);
                saglikIksiriAtildiMi = true;
            }
        } else {
            saglikIksiriAtildiMi = false;
        }

        if (buffBeklemesi > 0) {
            buffBeklemesi--;
        } else {
            boolean birSeyAtildi = false;
            if (!ben.hasStatusEffect(StatusEffects.SPEED)) {
                birSeyAtildi |= iksirVarsaAt(client, ben, HIZ_IKSIRLERI);
            }
            if (!ben.hasStatusEffect(StatusEffects.STRENGTH)) {
                birSeyAtildi |= iksirVarsaAt(client, ben, KUVVET_IKSIRLERI);
            }
            if (birSeyAtildi) {
                buffBeklemesi = BUFF_TEKRAR_BEKLEME;
            }
        }
    }

    private boolean iksirVarsaAt(MinecraftClient client, ClientPlayerEntity ben, List<RegistryEntry<Potion>> aranan) {
        int slot = iksirSlotuBul(ben, aranan);
        if (slot == -1) return false;

        if (slot >= 9) {
            slot = iksiriHotbaraTasi(client, ben, slot);
        }

        ayaginaAt(client, ben, slot);
        return true;
    }

    private int iksirSlotuBul(ClientPlayerEntity ben, List<RegistryEntry<Potion>> aranan) {
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = ben.getInventory().getStack(slot);
            if (stack.getItem() != Items.SPLASH_POTION) continue;

            PotionContentsComponent icerik = stack.get(DataComponentTypes.POTION_CONTENTS);
            if (icerik == null) continue;

            Optional<RegistryEntry<Potion>> potion = icerik.potion();
            if (potion.isPresent() && aranan.contains(potion.get())) {
                return slot;
            }
        }
        return -1;
    }

    private int iksiriHotbaraTasi(MinecraftClient client, ClientPlayerEntity ben, int envanterSlotu) {
        int syncId = ben.playerScreenHandler.syncId;
        client.interactionManager.clickSlot(syncId, envanterSlotu, IKSIR_TASIMA_SLOTU, SlotActionType.SWAP, ben);
        return IKSIR_TASIMA_SLOTU;
    }

    private void ayaginaAt(MinecraftClient client, ClientPlayerEntity ben, int slot) {
        int oncekiSlot = ben.getInventory().getSelectedSlot();
        ben.getInventory().setSelectedSlot(slot);

        float eskiPitch = ben.getPitch();
        ben.setPitch(90.0f);

        client.interactionManager.interactItem(ben, Hand.MAIN_HAND);

        ben.setPitch(eskiPitch);
        ben.getInventory().setSelectedSlot(oncekiSlot);
    }

    private void enIyiKiliciKusan(PlayerEntity ben) {
        ItemStack suankiEl = ben.getMainHandStack();
        if (suankiEl.isIn(ItemTags.SWORDS)) return;

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
            ben.getInventory().setSelectedSlot(enIyiSlot);
        }
    }
}
