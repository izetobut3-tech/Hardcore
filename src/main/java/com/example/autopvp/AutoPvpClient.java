package com.example.autopvp;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
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
    private static final double DONUS_YUMUSAKLIGI = 12.0;
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
    private int saglikBeklemesi = 0;
    private int buffSayaci = 0;

    private int toplamVurusSayaci = 0;
    private boolean zipAtildiMi = false;

    private volatile PlayerEntity mevcutHedef = null;
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
                if (AKTIF) {
                    buffSayaci = BUFF_ARALIGI;
                }
                if (client.player != null) {
                    client.player.sendMessage(
                            net.minecraft.text.Text.literal(AKTIF ? "AutoPvP: ACIK" : "AutoPvP: KAPALI"),
                            true
                    );
                }
                if (!AKTIF) {
                    mevcutHedef = null;
                }
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(this::herTick);

        // Her karede calisir (WorldRenderEvents yerine), goruntuyu pürüzsüz döndürmek için.
        HudRenderCallback.EVENT.register((drawContext, tickCounter) -> heKareDon());
    }

    private void heKareDon() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!AKTIF) return;
        if (client.player == null || client.world == null) return;

        PlayerEntity ben = client.player;
        PlayerEntity hedef = mevcutHedef;
        if (hedef == null || !hedef.isAlive()) return;

        long simdi = System.nanoTime();
        double gecenSaniye;
        if (sonKareZamani == 0L) {
            gecenSaniye = 1.0 / 60.0;
        } else {
            gecenSaniye = (simdi - sonKareZamani) / 1_000_000_000.0;
            gecenSaniye = MathHelper.clamp(gecenSaniye, 0.0, 0.1);
        }
        sonKareZamani = simdi;

        double yumusaklikOrani = 1.0 - Math.exp(-DONUS_YUMUSAKLIGI * gecenSaniye);
        hedefeBak(ben, hedef, (float) yumusaklikOrani);
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
            mevcutHedef = null;
            return;
        }

        PlayerEntity dusman = enYakinDusman(client, ben);
        mevcutHedef = dusman;
        if (dusman == null) return;

        double mesafe = ben.distanceTo(dusman);

        if (mesafe <= NISAN_MESAFESI) {
            enIyiKiliciKusan(ben);
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

    private void hedefeBak(PlayerEntity ben, PlayerEntity hedef, float yumusaklikOrani) {
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

        float yeniYaw = suankiYaw + farkYaw * yumusaklikOrani;
        float yeniPitch = suankiPitch + farkPitch * yumusaklikOrani;
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

        // 3 vurustan 2'si kritik olsun (sayac 0 ve 1 -> kritik, 2 -> normal)
        boolean kritIstenen = (toplamVurusSayaci % 3) != 2;

        if (kritIstenen) {
            boolean havadaDusuyor = !ben.isOnGround()
                    && ben.getVelocity().y < 0.0
                    && !ben.isClimbing()
                    && !ben.isTouchingWater()
                    && !ben.isSprinting();

            if (!havadaDusuyor) {
                ben.setSprinting(false);
                if (ben.isOnGround() && !zipAtildiMi) {
                    ben.jump();
                    zipAtildiMi = true;
                }
                return;
            }
        }

        zipAtildiMi = false;
        client.interactionManager.attackEntity(ben, dusman);
        ben.swingHand(Hand.MAIN_HAND);
        toplamVurusSayaci++;
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
