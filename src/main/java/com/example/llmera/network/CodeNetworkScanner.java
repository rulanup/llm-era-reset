package com.example.llmera.network;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class CodeNetworkScanner {
    private static final int MAX_SCAN_NODES = 512;

    private CodeNetworkScanner() {
    }

    public record NetworkView(CodeNetworkControllerBlockEntity controller,
                              List<CodeNetworkControllerBlockEntity> allControllers,
                              LlmConfiguratorBlockEntity configurator,
                              List<BlockPos> memberPositions,
                              int nodes,
                              boolean truncated,
                              int channelUsed,
                              int channelCapacity,
                              Set<BlockPos> membersWithChannels,
                              List<BlockPos> channelProblems,
                              Map<BlockPos, Integer> cableCapacities,
                              Map<BlockPos, Integer> cableUsage) {
        public NetworkView(CodeNetworkControllerBlockEntity controller,
                           LlmConfiguratorBlockEntity configurator,
                           List<BlockPos> memberPositions,
                           int nodes,
                           boolean truncated) {
            this(controller, List.of(controller), configurator, memberPositions, nodes, truncated, 0, 0,
                    Set.of(), List.copyOf(memberPositions), Map.of(), Map.of());
        }

        public boolean powered() {
            return controller != null && controller.isOnline();
        }

        public boolean memberHasChannel(BlockPos pos) {
            return membersWithChannels.contains(pos);
        }

        public int cableCapacity(BlockPos pos) {
            return cableCapacities.getOrDefault(pos, 0);
        }

        public int cableUsed(BlockPos pos) {
            return cableUsage.getOrDefault(pos, 0);
        }
    }

    private record MemberRoute(BlockPos member, int demand, List<BlockPos> path) {
    }

    private static final Comparator<BlockPos> POSITION_ORDER = Comparator
            .comparingInt((BlockPos pos) -> pos.getX())
            .thenComparingInt(BlockPos::getY)
            .thenComparingInt(BlockPos::getZ);

    public static NetworkView scan(ServerLevel level, BlockPos origin) {
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> seen = new HashSet<>();
        List<CodeNetworkControllerBlockEntity> controllers = new ArrayList<>();
        Set<BlockPos> memberPositionSet = new HashSet<>();
        Map<BlockPos, Integer> cableCapacities = new HashMap<>();
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
                controllers.add(c);
            }
            BlockState state = level.getBlockState(pos);
            if (isEndpoint(state, be)) {
                memberPositionSet.add(pos.immutable());
            }
            if (state.getBlock() instanceof CodeCableBlock cable) {
                cableCapacities.put(pos.immutable(), cable.channelCapacity());
            }
            boolean isOrigin = pos.equals(origin);
            boolean isCable = isCable(state);
            boolean isController = be instanceof CodeNetworkControllerBlockEntity;
            if (!isOrigin && !isCable && !isController) {
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

        List<BlockPos> memberPositions = memberPositionSet.stream().sorted(POSITION_ORDER).toList();

        // Use all connected controllers (like AE2 controller multiblock)
        List<CodeNetworkControllerBlockEntity> onlineControllers = controllers.stream()
                .filter(CodeNetworkControllerBlockEntity::isOnline)
                .sorted(Comparator.comparingInt(CodeNetworkControllerBlockEntity::getEnergyStored).reversed()
                        .thenComparing(CodeNetworkControllerBlockEntity::getBlockPos, POSITION_ORDER))
                .toList();

        if (onlineControllers.isEmpty()) {
            List<BlockPos> channelProblems = memberPositions.stream()
                    .filter(pos -> !(level.getBlockEntity(pos) instanceof CodeNetworkControllerBlockEntity))
                    .filter(pos -> !(level.getBlockState(pos).getBlock() instanceof CodeCableBlock))
                    .toList();
            return new NetworkView(null, List.of(), null, memberPositions, seen.size(), truncated, 0, 0,
                    Set.of(), channelProblems, Map.copyOf(cableCapacities), Map.of());
        }

        // Use the first controller as primary, but all controllers share the network
        CodeNetworkControllerBlockEntity controller = onlineControllers.getFirst();

        // Calculate channel capacity from ALL controllers' adjacent cables
        int channelCapacity = 0;
        Set<BlockPos> allControllerPositions = new HashSet<>();
        for (CodeNetworkControllerBlockEntity ctrl : onlineControllers) {
            allControllerPositions.add(ctrl.getBlockPos().immutable());
            for (Direction direction : Direction.values()) {
                channelCapacity += cableCapacities.getOrDefault(ctrl.getBlockPos().relative(direction), 0);
            }
        }

        BlockPos controllerPos = controller.getBlockPos();
        List<MemberRoute> routes = new ArrayList<>();
        for (BlockPos memberPos : memberPositions) {
            BlockEntity member = level.getBlockEntity(memberPos);
            if (member instanceof CodeNetworkControllerBlockEntity
                    || level.getBlockState(memberPos).getBlock() instanceof CodeCableBlock) {
                continue;
            }
            int demand = channelDemand(level.getBlockState(memberPos), member);
            // Find shortest path from any controller
            List<BlockPos> bestPath = null;
            for (CodeNetworkControllerBlockEntity ctrl : onlineControllers) {
                List<BlockPos> path = findCablePath(ctrl.getBlockPos(), memberPos, demand, cableCapacities, Map.of());
                if (path != null && (bestPath == null || path.size() < bestPath.size())) {
                    bestPath = path;
                }
            }
            if (bestPath != null) {
                routes.add(new MemberRoute(memberPos, demand, bestPath));
            }
        }
        routes.sort(Comparator.comparingInt((MemberRoute route) -> route.path().size())
                .thenComparing(MemberRoute::member, POSITION_ORDER));

        Map<BlockPos, Integer> cableUsage = new HashMap<>();
        Set<BlockPos> membersWithChannels = new LinkedHashSet<>();
        int allocatedChannels = 0;
        for (MemberRoute route : routes) {
            // Find available path from any controller
            List<BlockPos> availablePath = null;
            for (CodeNetworkControllerBlockEntity ctrl : onlineControllers) {
                availablePath = findCablePath(
                        ctrl.getBlockPos(), route.member(), route.demand(), cableCapacities, cableUsage);
                if (availablePath != null) break;
            }
            if (availablePath == null) {
                continue;
            }
            membersWithChannels.add(route.member());
            allocatedChannels += route.demand();
            for (BlockPos cablePos : availablePath) {
                cableUsage.merge(cablePos, route.demand(), Integer::sum);
            }
        }

        LlmConfiguratorBlockEntity configurator = membersWithChannels.stream()
                .map(level::getBlockEntity)
                .filter(LlmConfiguratorBlockEntity.class::isInstance)
                .map(LlmConfiguratorBlockEntity.class::cast)
                .findFirst()
                .orElse(null);
        List<BlockPos> channelProblems = memberPositions.stream()
                .filter(pos -> !(level.getBlockEntity(pos) instanceof CodeNetworkControllerBlockEntity))
                .filter(pos -> !(level.getBlockState(pos).getBlock() instanceof CodeCableBlock))
                .filter(pos -> !membersWithChannels.contains(pos))
                .toList();

        return new NetworkView(controller, onlineControllers, configurator, memberPositions, seen.size(), truncated,
                allocatedChannels, channelCapacity, Set.copyOf(membersWithChannels), channelProblems,
                orderedMap(cableCapacities), orderedMap(cableUsage));
    }

    private static List<BlockPos> findCablePath(BlockPos controllerPos,
                                                  BlockPos memberPos,
                                                  int demand,
                                                  Map<BlockPos, Integer> cableCapacities,
                                                  Map<BlockPos, Integer> cableUsage) {
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Map<BlockPos, BlockPos> parents = new HashMap<>();
        for (Direction direction : Direction.values()) {
            BlockPos cablePos = controllerPos.relative(direction).immutable();
            if (hasAvailableChannels(cablePos, demand, cableCapacities, cableUsage)
                    && !parents.containsKey(cablePos)) {
                parents.put(cablePos, null);
                queue.addLast(cablePos);
            }
        }

        while (!queue.isEmpty()) {
            BlockPos cablePos = queue.removeFirst();
            for (Direction direction : Direction.values()) {
                BlockPos next = cablePos.relative(direction).immutable();
                if (next.equals(memberPos)) {
                    return buildPath(cablePos, parents);
                }
                if (!parents.containsKey(next) && hasAvailableChannels(next, demand, cableCapacities, cableUsage)) {
                    parents.put(next, cablePos);
                    queue.addLast(next);
                }
            }
        }
        return null;
    }

    private static boolean hasAvailableChannels(BlockPos pos,
                                                 int demand,
                                                 Map<BlockPos, Integer> cableCapacities,
                                                 Map<BlockPos, Integer> cableUsage) {
        int capacity = cableCapacities.getOrDefault(pos, 0);
        return capacity - cableUsage.getOrDefault(pos, 0) >= demand;
    }

    private static int channelDemand(BlockState state, BlockEntity be) {
        CodeNetworkMember member = be instanceof CodeNetworkMember blockEntityMember
                ? blockEntityMember
                : state.getBlock() instanceof CodeNetworkMember blockMember ? blockMember : null;
        return member == null ? 1 : Math.max(1, member.channelDemand());
    }

    private static List<BlockPos> buildPath(BlockPos end, Map<BlockPos, BlockPos> parents) {
        ArrayList<BlockPos> reversed = new ArrayList<>();
        for (BlockPos current = end; current != null; current = parents.get(current)) {
            reversed.add(current);
        }
        java.util.Collections.reverse(reversed);
        return List.copyOf(reversed);
    }

    private static Map<BlockPos, Integer> orderedMap(Map<BlockPos, Integer> values) {
        Map<BlockPos, Integer> ordered = new LinkedHashMap<>();
        values.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(POSITION_ORDER))
                .forEach(entry -> ordered.put(entry.getKey(), entry.getValue()));
        return java.util.Collections.unmodifiableMap(ordered);
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
