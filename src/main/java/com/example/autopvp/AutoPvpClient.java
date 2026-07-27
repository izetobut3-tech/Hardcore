package com.example.autopvp;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.Input;
import net.minecraft.client.network.ClientPlayerEntity;
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
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.lwjgl.glfw.GLFW;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public class AutoPvpClient implements ClientModInitializer {

    private static final double SALDIRI_MESAFESI = 3.5;
    // 1.9 PvP mantigi: vurus ancak silahin cooldown gostergesi (attack indicator)
    // TAM dolunca yapilir. getAttackCooldownProgress ayni HUD'daki gostergenin
    // kullandigi degeri dondurur; 1.0f = gosterge tam dolu.
    private static final float TAM_GUC_ESIGI = 1.0f;
    // Can bu degerin ALTINA dusunce saglik iksiri atilir (tam can degil).
    private static final float SAGLIK_ESIGI = 8.0f;
    // Nisan (aim-lock) mesafesi, tarama/yurume mesafesinden AYRI ve daha kisa.
    // Bot artik uzaktaki rakiplere kilitlenmeyecek, sadece bu mesafedeki rakiplere.
    private static final double NISAN_MESAFESI = 6.0;
    // Buff (kuvvet/hiz) iksirini atmalar arasi bekleme suresi (tick). 20 tick = 1 saniye.
    private static final int BUFF_ARALIGI = 20 * 90; // 1.5 dakikada bir
    private static final double TARAMA_MESAFESI = 35.0;
    private static final float TAM_CAN = 20.0f;
    private static final float RENDER_DONUS_YUMUSAKLIGI = 0.15f;
    public static boolean AKTIF = false;

    private static final List<String> KILIC_SIRASI = Arrays.asList(
            "minecraft:netherite_sword",
            "minecraft:diamond_sword",
            "minecraft:iron_sword",
            "minecraft:golden_sword",
            "minecraft:stone_sword",
            "minecraft:wooden_sword"
    );

    // Sadece PATLAYICI (splash) iksirler kullanilir; hem 1. hem 2. seviye kabul edilir.
    // Icilebilir (drink) iksir ASLA kullanilmaz.
    private static final List<RegistryEntry<Potion>> SAGLIK_IKSIRLERI = Arrays.asList(
            Potions.HEALING,
            Potions.STRONG_HEALING
    );
    private static final List<RegistryEntry<Potion>> BUFF_IKSIRLERI = Arrays.asList(
            Potions.STRENGTH,
            Potions.STRONG_STRENGTH,
            Potions.SWIFTNESS,
            Potions.STRONG_SWIFTNESS
    );
    // Iksir ana 9 slotta (hotbar) yoksa, envanterin geri kalanindan (9-35)
    // alinip bu hotbar slotuna tasinir, sonra oradan kullanilir.
    private static final int IKSIR_TASIMA_SLOTU = 8;

    private int zipEskimeSayaci = 0;
    private int buffBeklemesi = 0;
    // Can TAM_CAN esiginin altina her dustugunde SADECE BIR KEZ saglik iksiri
    // atilmasini saglar. Can tekrar TAM_CAN'a cikip yeniden dusmeden ikinci
    // atis yapilmaz.
    private boolean saglikIksiriAtildiMi = false;

    private static final KeyBinding.Category AUTOPVP_KATEGORI =
            KeyBinding.Category.create(Identifier.of("autopvp", "main"));

    private static KeyBinding ACKAPA_TUSU;

    private Input gercekGirdi = null;
    private boolean zorlaGirdiAktif = false;

    private static class BotGirdisi extends Input {
    }

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
                if (!AKTIF) {
                    girdiyiGeriVer(client);
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

        WorldRenderEvents.START.register(context -> {
            if (!AKTIF) return;
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null || client.world == null) return;

            PlayerEntity dusman = enYakinDusman(client, client.player);
            if (dusman == null) return;

            // Sadece nisan mesafesi icindeyse VE gorus hatti acikken kilitlen.
            double mesafe = client.player.distanceTo(dusman);
            if (mesafe > NISAN_MESAFESI) return;
            if (!gorusHattiAcikMi(client.player, dusman)) return;

            hedefeBak(client.player, dusman, RENDER_DONUS_YUMUSAKLIGI);
        });
    }

    private void herTick(MinecraftClient client) {
        if (!AKTIF) return;
        if (client.player == null || client.world == null || client.interactionManager == null) return;

        ClientPlayerEntity ben = client.player;

        potIcIhtiyacVarsa(client, ben);

        if (ben.isUsingItem()) {
            // Elle iksir icerken (drink animasyonu surerken) saldirmiyoruz ki
            // yudumlama yarida kesilip bosa gitmesin. Icme bitince kaldigi
            // yerden devam eder.
            duz(ben);
            return;
        }

        PlayerEntity dusman = enYakinDusman(client, ben);
        if (dusman == null) {
            duz(ben);
            return;
        }

        enIyiKiliciKusan(ben);

        double mesafe = ben.distanceTo(dusman);

        if (mesafe <= SALDIRI_MESAFESI) {
            duz(ben);
            saldir(client, ben, dusman);
        } else {
            hedefeYuru(ben);
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

    // Duvar/blok arkasindaki rakibe bakmayi engellemek icin goz hizasindan raycast atar.
    private boolean gorusHattiAcikMi(PlayerEntity ben, PlayerEntity hedef) {
        Vec3d baslangic = ben.getEyePos();
        Vec3d bitis = hedef.getEyePos();

        RaycastContext context = new RaycastContext(
                baslangic,
                bitis,
                RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE,
                ben
        );

        HitResult sonuc = ben.getWorld().raycast(context);
        // Blok arasina hicbir sey girmediyse MISS doner, yani gorus hatti acik demektir.
        return sonuc.getType() == HitResult.Type.MISS;
    }

    private void hedefeBak(PlayerEntity ben, PlayerEntity hedef, float donusOrani) {
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

        float yeniYaw = suankiYaw + farkYaw * donusOrani;
        float yeniPitch = suankiPitch + farkPitch * donusOrani;
        yeniPitch = MathHelper.clamp(yeniPitch, -90.0f, 90.0f);

        ben.setYaw(yeniYaw);
        ben.setPitch(yeniPitch);
        ben.setBodyYaw(yeniYaw);
        ben.setHeadYaw(yeniYaw);
    }

    private void hedefeYuru(ClientPlayerEntity ben) {
        zorlaGirdiUygula(ben, true, false);
        if (ben.horizontalCollision && ben.isOnGround()) {
            ben.jump();
        }
    }

    private void duz(PlayerEntity ben) {
        girdiyiGeriVer(MinecraftClient.getInstance());
    }

    private void zorlaGirdiUygula(ClientPlayerEntity ben, boolean ileri, boolean geri) {
        if (!zorlaGirdiAktif) {
            gercekGirdi = ben.input;
            ben.input = new BotGirdisi();
            zorlaGirdiAktif = true;
        }
        ben.input.playerInput = new PlayerInput(ileri, geri, false, false, false, false, false);
    }

    private void girdiyiGeriVer(MinecraftClient client) {
        if (zorlaGirdiAktif && client != null && client.player != null && gercekGirdi != null) {
            client.player.input = gercekGirdi;
        }
        zorlaGirdiAktif = false;
        gercekGirdi = null;
    }

    private void saldir(MinecraftClient client, PlayerEntity ben, PlayerEntity dusman) {
        // Gosterge (attack indicator) tam dolmadan vurma.
        if (ben.getAttackCooldownProgress(0.0f) < TAM_GUC_ESIGI) {
            return;
        }

        zipEskimeSayaci++;
        if (zipEskimeSayaci % 3 == 0 && ben.isOnGround()) {
            ben.jump();
        }

        client.interactionManager.attackEntity(ben, dusman);
        ben.swingHand(Hand.MAIN_HAND);
    }

    private void potIcIhtiyacVarsa(MinecraftClient client, ClientPlayerEntity ben) {
        if (ben.getHealth() < SAGLIK_ESIGI) {
            // Bu "dususte" daha once atmadiysak, tek seferlik at.
            if (!saglikIksiriAtildiMi) {
                iksirVarsaAt(client, ben, SAGLIK_IKSIRLERI);
                // Iksir bulunamasa bile bayragi kaldiriyoruz ki her tick
                // bos yere envanteri taramasin; can yine esigin altina dusene
                // (yani once esigin ustune cikip tekrar dusene) kadar denenmez.
                saglikIksiriAtildiMi = true;
            }
        } else {
            // Can esigin ustune cikinca bayragi sifirla, bir sonraki dususte tekrar atabilsin.
            saglikIksiriAtildiMi = false;
        }

        // Belirli araliklarla kuvvet/hiz iksiri at.
        if (buffBeklemesi > 0) {
            buffBeklemesi--;
        } else if (iksirVarsaAt(client, ben, BUFF_IKSIRLERI)) {
            buffBeklemesi = BUFF_ARALIGI;
        }
    }

    // Envanterin TAMAMINDA (hotbar + ana envanter, 0-35) verilen iksir listesine
    // uyan bir SPLASH_POTION arar; hotbar disindaysa once hotbar'a tasir, sonra atar.
    private boolean iksirVarsaAt(MinecraftClient client, ClientPlayerEntity ben, List<RegistryEntry<Potion>> aranan) {
        int slot = iksirSlotuBul(ben, aranan);
        if (slot == -1) return false; // envanterde hic yok, bir sey yapma

        if (slot >= 9) {
            // Ana envanterden (backpack) hotbar'daki tasima slotuna al.
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

    // Ana envanterdeki (9-35) bir esyayi, hotbar'daki IKSIR_TASIMA_SLOTU ile takas eder.
    // PlayerScreenHandler'da ana envanter slot id'leri PlayerInventory index'leriyle
    // birebir (9-35), hotbar ise 36-44 araliginda; SWAP butonu hedef hotbar index'idir (0-8).
    private int iksiriHotbaraTasi(MinecraftClient client, ClientPlayerEntity ben, int envanterSlotu) {
        int syncId = ben.playerScreenHandler.syncId;
        client.interactionManager.clickSlot(syncId, envanterSlotu, IKSIR_TASIMA_SLOTU, SlotActionType.SWAP, ben);
        return IKSIR_TASIMA_SLOTU;
    }

    // Iksiri kafadan yukari degil, ASAGI (kendi ayaginin dibine) firlatir.
    // Boylece havada beklemeden aninda patlar, "yavas atma" sorunu cozulur.
    private void ayaginaAt(MinecraftClient client, ClientPlayerEntity ben, int slot) {
        int oncekiSlot = ben.getInventory().getSelectedSlot();
        ben.getInventory().setSelectedSlot(slot);

        float eskiPitch = ben.getPitch();
        ben.setPitch(90.0f); // 90 = asagi bak (Minecraft'ta -90 yukari, 90 asagidir)

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
