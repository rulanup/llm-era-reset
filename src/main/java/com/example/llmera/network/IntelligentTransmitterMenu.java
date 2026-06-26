package com.example.llmera.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

public class IntelligentTransmitterMenu extends AbstractContainerMenu {
    public final BlockPos pos;
    public final boolean conversationMode;

    public IntelligentTransmitterMenu(int containerId, Inventory playerInv,
                                      BlockPos pos, boolean conversationMode) {
        super(LLMEraMenus.TRANSMITTER.get(), containerId);
        this.pos = pos;
        this.conversationMode = conversationMode;
    }

    /** Client-side ctor reads BlockPos + boolean from the open-menu buffer. */
    public static IntelligentTransmitterMenu clientFromBuffer(int containerId, Inventory playerInv,
                                                                RegistryFriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        boolean conv = buf.readableBytes() > 0 && buf.readBoolean();
        return new IntelligentTransmitterMenu(containerId, playerInv, pos, conv);
    }

    public IntelligentTransmitterBlockEntity te(Player player) {
        return player.level().getBlockEntity(pos) instanceof IntelligentTransmitterBlockEntity t ? t : null;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        if (player.level().getBlockEntity(pos) == null) return false;
        return player.distanceToSqr(
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= 64.0;
    }
}
