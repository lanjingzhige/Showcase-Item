package com.ultramega.showcaseitem;

import com.ultramega.showcaseitem.config.Config;
import com.ultramega.showcaseitem.network.ShareItemData;

import java.util.List;

import javax.annotation.Nullable;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.FormattedCharSink;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.settings.KeyModifier;
import net.neoforged.neoforge.network.PacketDistributor;

@EventBusSubscriber(modid = ShowcaseItem.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public class ShowcaseItemFeature {
    public static float alphaValue = 1F;

    /**
     * The item is rendered at half size, so it occupies {@value #ICON_SIZE} pixels of a chat line.
     */
    private static final float ICON_SCALE = 0.5F;
    private static final float ICON_SIZE = 16.0F * ICON_SCALE;

    /**
     * The item marker is a run of spaces that precedes the item name, see {@link #createStackComponent}.
     */
    private static final int MARKER_MIN_SPACES = 2;

    /**
     * When a message is wrapped, the line break eats the space it breaks at, so the line before the item name
     * can end with the leftover of the marker. That leftover still needs one space more than a complete marker
     * to be a marker of its own, otherwise the icon belongs to the item name on the next line.
     */
    private static final int MARKER_TRAILING_MIN_SPACES = MARKER_MIN_SPACES + 1;

    private static long lastShadeTimestamp = -1;

    @OnlyIn(Dist.CLIENT)
    public static void renderItemForMessage(GuiGraphics guiGraphics, FormattedCharSequence sequence, float x, float y, int color) {
        if (!Config.renderItemsInChat)
            return;

        ItemMarkerSink sink = new ItemMarkerSink(guiGraphics, x, y, color);
        if (sequence.accept(sink)) {
            //the item name can be wrapped onto the next line, leaving the marker at the end of this one
            sink.renderTrailingMarker();
        }
    }

    /**
     * Looks for the marker that precedes the name of a shared item and renders the item icon on top of it.
     */
    @OnlyIn(Dist.CLIENT)
    private static final class ItemMarkerSink implements FormattedCharSink {
        private final Minecraft mc;
        private final GuiGraphics guiGraphics;
        private final float x;
        private final float y;
        private final int color;
        private final StringBuilder text = new StringBuilder();

        private Style spaceStyle;
        private boolean rendered;

        private ItemMarkerSink(GuiGraphics guiGraphics, float x, float y, int color) {
            this.mc = Minecraft.getInstance();
            this.guiGraphics = guiGraphics;
            this.x = x;
            this.y = y;
            this.color = color;
        }

        @Override
        public boolean accept(int position, Style style, int character) {
            if (character == ' ') {
                this.spaceStyle = style;
                this.text.append(' ');
                return true;
            }

            int markerSpaces = this.countMarkerSpaces();
            if (markerSpaces >= MARKER_MIN_SPACES) {
                this.renderItem(style, this.spaceStyle, markerSpaces);
                return false;
            }

            this.text.appendCodePoint(character);
            return true;
        }

        private void renderTrailingMarker() {
            int markerSpaces = this.countMarkerSpaces();
            if (markerSpaces >= MARKER_TRAILING_MIN_SPACES) {
                this.renderItem(this.spaceStyle, this.spaceStyle, markerSpaces);
            }
        }

        private int countMarkerSpaces() {
            int spaces = 0;
            for (int i = this.text.length() - 1; i >= 0 && this.text.charAt(i) == ' '; i--) {
                spaces++;
            }
            return spaces;
        }

        private void renderItem(Style style, Style markerStyle, int markerSpaces) {
            if (this.rendered)
                return;

            this.rendered = true;
            render(this.mc, this.guiGraphics, this.text.substring(0, this.text.length() - markerSpaces), markerSpaces, this.x, this.y, style, markerStyle, this.color);
        }
    }

    @SubscribeEvent
    @OnlyIn(Dist.CLIENT)
    public static void keyboardEvent(ScreenEvent.KeyPressed.Pre event) {
        if (ModKeyBindings.SHOWCASE_ITEM.isUnbound()) return;

        Minecraft mc = Minecraft.getInstance();
        if (InputConstants.isKeyDown(mc.getWindow().getWindow(), ModKeyBindings.SHOWCASE_ITEM.getKey().getValue()) && keyModifierPressed(mc)) {
            keyPressed();
        }
    }

    public static void keyPressed() {
        Minecraft mc = Minecraft.getInstance();
        Screen screen = mc.screen;

        if (screen instanceof AbstractContainerScreen<?> gui) {
            List<? extends GuiEventListener> children = gui.children();
            for (GuiEventListener c : children)
                if (c instanceof EditBox tf) {
                    if (tf.isFocused())
                        return;
                }

            Slot slot = gui.getSlotUnderMouse();
            if (slot != null) {
                ItemStack stack = slot.getItem();

                if (!stack.isEmpty()) {
                    if (mc.level != null && mc.level.getGameTime() - lastShadeTimestamp > 10) {
                        lastShadeTimestamp = mc.level.getGameTime();
                    } else
                        return;

                    PacketDistributor.sendToServer(new ShareItemData(slot.getSlotIndex(), gui.getMenu().containerId));
                }
            }
        }
    }

    public static void shareItem(ServerPlayer player, int slotIndex, int containerId) {
        if (player.containerMenu.containerId != containerId) return;

        NonNullList<Slot> slots = player.containerMenu.slots;
        if (slotIndex >= 0 && slots.size() > slotIndex) {
            ItemStack stack;
            // Creative menu support
            if (player.containerMenu instanceof InventoryMenu) {
                stack = player.getInventory().getItem(slotIndex);
            } else {
                Slot slot = slots.get(slotIndex);
                stack = slot.getItem();
            }
            if (!stack.isEmpty()) {
                MutableComponent message = Component
                        .translatable("showcaseitem.misc.shared_item", player.getName())
                        .append(stack.getDisplayName());

                player.server.getPlayerList().getPlayers().forEach(p -> p.sendSystemMessage(message));
            }
        }
    }

    public static MutableComponent createStackComponent(ItemStack stack, MutableComponent component) {
        if (!Config.renderItemsInChat)
            return component;

        Style style = component.getStyle();
        if (stack.getCount() > 64) {
            ItemStack copyStack = stack.copy();
            copyStack.setCount(64);
            style = style.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_ITEM, new HoverEvent.ItemStackInfo(copyStack)));
            component.withStyle(style);
        }

        MutableComponent out = Component.literal("   ");
        out.setStyle(style);
        return out.append(component);
    }

    @OnlyIn(Dist.CLIENT)
    private static void render(Minecraft mc, GuiGraphics graphics, String before, int markerSpaces, float x, float y, Style style, Style markerStyle, int color) {
        float a = (color >> 24 & 255) / 255.0F;

        HoverEvent hoverEvent = itemHoverEvent(style.getHoverEvent());
        if (hoverEvent == null && markerStyle != null) {
            hoverEvent = itemHoverEvent(markerStyle.getHoverEvent());
        }
        if (hoverEvent != null) {
            HoverEvent.ItemStackInfo contents = hoverEvent.getValue(HoverEvent.Action.SHOW_ITEM);

            ItemStack stack = contents != null ? contents.getItemStack() : ItemStack.EMPTY;

            if (stack.isEmpty())
                stack = new ItemStack(Blocks.BARRIER); // For invalid icon

            //center the icon in the space that the marker reserved for it, so it lines up no matter
            //how many spaces the marker consists of (messages that got wrapped use less of them)
            float markerWidth = mc.font.width(" ") * markerSpaces;
            float shift = mc.font.width(before) + (markerWidth - ICON_SIZE) / 2.0F;

            // Fix y-shift if overflowingbars is installed
            if (ModList.get().isLoaded("overflowingbars")) {
                Player player = Minecraft.getInstance().player;
                if (player != null) {
                    y += player.getAbsorptionAmount() > 10.0F ? 10 : 0;
                    y += player.getArmorValue() > 0.5F ? 10 : 0;
                }
            }

            if (a > 0) {
                alphaValue = a;

                PoseStack pose = graphics.pose();
                pose.pushPose();

                pose.translate(shift + x, y, 0);
                pose.scale(ICON_SCALE, ICON_SCALE, ICON_SCALE);

                graphics.renderItem(stack, 0, 0);

                pose.popPose();

                RenderSystem.applyModelViewMatrix();

                alphaValue = 1F;
            }
        }
    }

    @Nullable
    private static HoverEvent itemHoverEvent(@Nullable HoverEvent hoverEvent) {
        return hoverEvent != null && hoverEvent.getAction() == HoverEvent.Action.SHOW_ITEM ? hoverEvent : null;
    }

    private static boolean keyModifierPressed(Minecraft mc) {
        int keyModifierInt = checkLeftKeyModifier();
        int keyModifierInt2 = checkRightKeyModifier();

        if (keyModifierInt != -1)
            return InputConstants.isKeyDown(mc.getWindow().getWindow(), keyModifierInt);
        else if (keyModifierInt2 != -1)
            return InputConstants.isKeyDown(mc.getWindow().getWindow(), keyModifierInt2);

        return true;
    }

    private static int checkLeftKeyModifier() {
        KeyModifier keyModifier = ModKeyBindings.SHOWCASE_ITEM.getKeyModifier();
        int keyModifierInt = -1;
        if (keyModifier.equals(KeyModifier.CONTROL))
            keyModifierInt = 341;
        else if (keyModifier.equals(KeyModifier.ALT))
            keyModifierInt = 342;
        else if (keyModifier.equals(KeyModifier.SHIFT))
            keyModifierInt = 340;
        return keyModifierInt;
    }

    private static int checkRightKeyModifier() {
        KeyModifier keyModifier = ModKeyBindings.SHOWCASE_ITEM.getKeyModifier();
        int keyModifierInt = -1;
        if (keyModifier.equals(KeyModifier.CONTROL)) {
            keyModifierInt = 345;
        } else if (keyModifier.equals(KeyModifier.ALT)) {
            keyModifierInt = 346;
        } else if (keyModifier.equals(KeyModifier.SHIFT)) {
            keyModifierInt = 344;
        }
        return keyModifierInt;
    }
}
