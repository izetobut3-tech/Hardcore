package com.example.autopvp;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.PotionContentsComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.potion.Potion;
import net.minecraft.potion.Potions;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;

import java.util.Arrays;
import java.util.List;

public class AutoPvpClient implements ClientModInitializer {

    private static final double SALDIRI_MESAFESI = 3.5;
    private static final double NISAN_MESAFESI = 12.0;
    private static final double TARAMA_MESAFESI = 35.0;
    private static final float KACIS_CAN_ESIGI = 8.0f;
    private static final float TAM_CAN = 20.0f;
    private static final float DONUS_HIZI = 22.0f;
    private static final int BUFF_ARALIGI = 1800;
    public static boolean AKTIF = true;

    private static final List<String> KILIC_SIRASI = Arrays.asList(
            "minecraft:netherite_sword",
            "minecraft:diamond_sword",
            "minecraft:iron_sword",
            "minecraft:golden_sword",
            "minecraft:stone_sword",
            "minecraft:wooden_sword"
    );

    private boolean acilDurumMu = false;
    private int vurusBeklemesi = 0;
    private int zipEskimeSayaci = 0;
    private int saglikBeklemesi = 0;
    private int buffSayaci = 0;

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
                if (AKTIF) {
                    buffSayaci = BUFF_ARALIGI;
                }
                if (client.player != null) {
                    client.player.sendMessage(
                            net.minecraft.text.Text.literal(AKTIF ? "AutoPvP: ACIK" : "AutoPvP: KAPALI"),
                            true
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
        if (saglikBeklemesi > 0) saglikBeklemesi--;

        buffSayaci++;
        if (buffSayaci >= BUFF_ARALIGI) {
            guclendirmeAt(client, ben);
            buffSayaci = 0;
        }

        if (ben.getHealth() <= KACIS_CAN_ESIGI) {
            acilDurumMu = true;
        } else if (ben.getHealth() >= TAM_CAN) {
            acilDurumMu = false;
        }

        if (acilDurumMu) {
            sifaIksiriAt(client, ben);
            return;
        }

        PlayerEntity dusman = enYakinDusman(client, ben);
        if (dusman == null) return;

        double mesafe = ben.distanceTo(dusman);

        if (mesafe <= NISAN_MESAFESI) {
            enIyiKiliciKusan(ben);
            hedefeBak(ben, dusman, DONUS_HIZI);
        }

        if (mesafe <= SALDIRI_MESAFESI) {
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

    private void hedefeBak(PlayerEntity ben, PlayerEntity hedef, float donusHizi) {
        double dx = hedef.getX() - ben.getX();
        double dy = hedef.getEyeY() - ben.getEyeY();
        double dz = hedef.getZ() - ben.getZ();
        double yatay = Math.sqrt(dx * dx + dz * dz);

        float hedefYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float hedefPitch = (float) -Math.toDegrees(Math.atan2(dy, yatay));

        float suankiYaw = ben.getYaw();
        float suankiPitch = ben.getPitch();

        float farkYaw = MathHelper.wrapDegrees(hedefYaw - suankiYaw);
        float farkPitch = hedefPitch - suankiPitch;

        float yeniYaw = suankiYaw + MathHelper.clamp(farkYaw, -donusHizi, donusHizi);
        float yeniPitch = suankiPitch + MathHelper.clamp(farkPitch, -donusHizi, donusHizi);
        yeniPitch = MathHelper.clamp(yeniPitch, -90.0f, 90.0f);

        ben.setYaw(yeniYaw);
        ben.setPitch(yeniPitch);
        ben.setBodyYaw(yeniYaw);
        ben.setHeadYaw(yeniYaw);
    }

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

        vurusBeklemesi = 4;
    }

    private int potionSlotuBul(PlayerEntity ben, RegistryEntry<Potion> aranan) {
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = ben.getInventory().getStack(slot);
            if (stack.getItem() != Items.SPLASH_POTION) continue;
            PotionContentsComponent pcc = stack.get(DataComponentTypes.POTION_CONTENTS);
            if (pcc == null) continue;
            if (pcc.potion().isPresent() && pcc.potion().get().equals(aranan)) {
                return slot;
            }
        }
        return -1;
    }

    private void ayaginaAt(MinecraftClient client, PlayerEntity ben, int slot) {
        int oncekiSlot = ben.getInventory().getSelectedSlot();
        ben.getInventory().setSelectedSlot(slot);

        float eskiPitch = ben.getPitch();
        ben.setPitch(-90.0f);

        client.interactionManager.interactItem(ben, Hand.MAIN_HAND);

        ben.setPitch(eskiPitch);
        ben.getInventory().setSelectedSlot(oncekiSlot);
    }

    private void sifaIksiriAt(MinecraftClient client, PlayerEntity ben) {
        if (saglikBeklemesi > 0) return;

        int slot = potionSlotuBul(ben, Potions.STRONG_HEALING);
        if (slot == -1) slot = potionSlotuBul(ben, Potions.HEALING);
        if (slot == -1) return;

        ayaginaAt(client, ben, slot);
        saglikBeklemesi = 30;
    }

    private void guclendirmeAt(MinecraftClient client, PlayerEntity ben) {
        int hizSlot = potionSlotuBul(ben, Potions.STRONG_SWIFTNESS);
        if (hizSlot != -1) {
            ayaginaAt(client, ben, hizSlot);
        }

        int kuvvetSlot = potionSlotuBul(ben, Potions.STRONG_STRENGTH);
        if (kuvvetSlot != -1) {
            ayaginaAt(client, ben, kuvvetSlot);
        }
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
