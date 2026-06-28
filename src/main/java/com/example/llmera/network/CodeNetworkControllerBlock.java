package com.example.llmera.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;

public class CodeNetworkControllerBlock extends Block implements EntityBlock, CodeNetworkMember {
    public static final BooleanProperty ONLINE = BooleanProperty.create("online");

    public CodeNetworkControllerBlock() {
        super(Properties.of().mapColor(MapColor.METAL).strength(4.0f));
        registerDefaultState(defaultBlockState().setValue(ONLINE, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(ONLINE);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CodeNetworkControllerBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hitResult) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof CodeNetworkControllerBlockEntity controller) {
            player.displayClientMessage(Component.literal("Code Network Controller: "
                    + controller.getEnergyStored() + "/" + controller.getMaxEnergyStored() + " FE"), true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
