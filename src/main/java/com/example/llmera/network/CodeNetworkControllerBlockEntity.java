package com.example.llmera.network;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.energy.IEnergyStorage;

public class CodeNetworkControllerBlockEntity extends BlockEntity implements CodeNetworkMember {
    private static final String TAG_ENERGY = "Energy";
    private static final int CAPACITY = 100_000;
    private static final int MAX_RECEIVE = 2_000;
    private static final int MIN_ONLINE_ENERGY = 100;
    private int energy;

    private final IEnergyStorage energyStorage = new IEnergyStorage() {
        @Override
        public int receiveEnergy(int maxReceive, boolean simulate) {
            int received = Math.min(MAX_RECEIVE, Math.min(maxReceive, CAPACITY - energy));
            if (!simulate && received > 0) {
                energy += received;
                markEnergyChanged();
            }
            return received;
        }

        @Override
        public int extractEnergy(int maxExtract, boolean simulate) {
            return 0;
        }

        @Override
        public int getEnergyStored() {
            return energy;
        }

        @Override
        public int getMaxEnergyStored() {
            return CAPACITY;
        }

        @Override
        public boolean canExtract() {
            return false;
        }

        @Override
        public boolean canReceive() {
            return true;
        }
    };

    public CodeNetworkControllerBlockEntity(BlockPos pos, BlockState state) {
        super(LLMEraBlockEntities.CODE_NETWORK_CONTROLLER.get(), pos, state);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        updateOnlineState();
    }

    public IEnergyStorage energyStorage() {
        return energyStorage;
    }

    public int getEnergyStored() {
        return energy;
    }

    public int getMaxEnergyStored() {
        return CAPACITY;
    }

    public boolean isOnline() {
        return energy >= MIN_ONLINE_ENERGY;
    }

    public boolean consumeEnergy(int amount) {
        if (amount <= 0) {
            return true;
        }
        if (energy < amount) {
            return false;
        }
        energy -= amount;
        markEnergyChanged();
        return true;
    }

    private void markEnergyChanged() {
        setChanged();
        updateOnlineState();
    }

    private void updateOnlineState() {
        if (level == null || level.isClientSide) {
            return;
        }
        BlockState state = getBlockState();
        if (state.hasProperty(CodeNetworkControllerBlock.ONLINE)
                && state.getValue(CodeNetworkControllerBlock.ONLINE) != isOnline()) {
            level.setBlock(worldPosition, state.setValue(CodeNetworkControllerBlock.ONLINE, isOnline()), 3);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.saveAdditional(tag, provider);
        tag.putInt(TAG_ENERGY, energy);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.loadAdditional(tag, provider);
        energy = Math.max(0, Math.min(CAPACITY, tag.getInt(TAG_ENERGY)));
    }
}
