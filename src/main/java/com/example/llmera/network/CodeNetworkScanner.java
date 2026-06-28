package com.example.llmera.network;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class CodeNetworkScanner {
    private static final int MAX_SCAN_NODES = 512;

    private CodeNetworkScanner() {
    }

    public record NetworkView(CodeNetworkControllerBlockEntity controller,
                              LlmConfiguratorBlockEntity configurator,
                              List<Ae2NetworkBridgeBlockEntity> ae2Bridges,
                              int nodes,
                              boolean truncated) {
        public boolean powered() {
            return controller != null && controller.isOnline();
        }
    }

    public static NetworkView scan(ServerLevel level, BlockPos origin) {
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> seen = new HashSet<>();
        CodeNetworkControllerBlockEntity controller = null;
        LlmConfiguratorBlockEntity configurator = null;
        List<Ae2NetworkBridgeBlockEntity> ae2Bridges = new ArrayList<>();
        boolean truncated = false;

        queue.add(origin.immutable());
        while (!queue.isEmpty()) {
            if (seen.size() >= MAX_SCAN_NODES) {
                truncated = true;
                break;
            }

            BlockPos pos = queue.removeFirst();
            if (!seen.add(pos)) {
                continue;
            }

            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof CodeNetworkControllerBlockEntity c) {
                if (controller == null || c.getEnergyStored() > controller.getEnergyStored()) {
                    controller = c;
                }
            } else if (be instanceof LlmConfiguratorBlockEntity cfg) {
                if (configurator == null) {
                    configurator = cfg;
                }
            } else if (be instanceof Ae2NetworkBridgeBlockEntity bridge) {
                ae2Bridges.add(bridge);
            }

            BlockState state = level.getBlockState(pos);
            boolean isOrigin = pos.equals(origin);
            boolean isCable = isCable(state);
            if (!isOrigin && !isCable) {
                continue;
            }

            for (Direction dir : Direction.values()) {
                BlockPos next = pos.relative(dir);
                if (seen.contains(next)) {
                    continue;
                }
                BlockEntity nextBe = level.getBlockEntity(next);
                BlockState nextState = level.getBlockState(next);
                if (isCable(nextState) || (isCable && isEndpoint(nextState, nextBe))) {
                    queue.add(next.immutable());
                }
            }
        }

        return new NetworkView(controller, configurator, List.copyOf(ae2Bridges), seen.size(), truncated);
    }

    public static boolean canCableConnect(ServerLevel level, BlockPos cablePos, Direction side) {
        BlockPos target = cablePos.relative(side);
        BlockEntity be = level.getBlockEntity(target);
        BlockState state = level.getBlockState(target);
        return isCable(state) || isEndpoint(state, be) || be != null;
    }

    private static boolean isCable(BlockState state) {
        return state.getBlock() instanceof CodeCableBlock;
    }

    private static boolean isEndpoint(BlockState state, BlockEntity be) {
        return state.getBlock() instanceof CodeNetworkMember || be instanceof CodeNetworkMember;
    }
}
