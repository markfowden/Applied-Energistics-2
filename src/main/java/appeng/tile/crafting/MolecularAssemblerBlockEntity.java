/*
 * This file is part of Applied Energistics 2.
 * Copyright (c) 2013 - 2014, AlgorithmX2, All rights reserved.
 *
 * Applied Energistics 2 is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Applied Energistics 2 is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Applied Energistics 2.  If not, see <http://www.gnu.org/licenses/lgpl>.
 */

package appeng.tile.crafting;

import java.io.IOException;
import java.util.List;

import javax.annotation.Nullable;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.inventory.CraftingInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import alexiil.mc.lib.attributes.Simulation;
import alexiil.mc.lib.attributes.item.FixedItemInv;

import appeng.api.config.Actionable;
import appeng.api.config.PowerMultiplier;
import appeng.api.config.RedstoneMode;
import appeng.api.config.Settings;
import appeng.api.config.Upgrades;
import appeng.api.definitions.ITileDefinition;
import appeng.api.implementations.IPowerChannelState;
import appeng.api.implementations.IUpgradeableHost;
import appeng.api.implementations.tiles.ICraftingMachine;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.networking.events.MENetworkEventSubscribe;
import appeng.api.networking.events.MENetworkPowerStatusChange;
import appeng.api.networking.ticking.IGridTickable;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.api.networking.ticking.TickingRequest;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.util.AECableType;
import appeng.api.util.AEPartLocation;
import appeng.api.util.DimensionalCoord;
import appeng.api.util.IConfigManager;
import appeng.container.ContainerNull;
import appeng.core.Api;
import appeng.core.sync.network.NetworkHandler;
import appeng.core.sync.network.TargetPoint;
import appeng.core.sync.packets.AssemblerAnimationPacket;
import appeng.items.misc.EncodedPatternItem;
import appeng.me.GridAccessException;
import appeng.parts.automation.DefinitionUpgradeInventory;
import appeng.parts.automation.UpgradeInventory;
import appeng.tile.grid.AENetworkInvBlockEntity;
import appeng.tile.inventory.AppEngInternalInventory;
import appeng.util.ConfigManager;
import appeng.util.IConfigManagerHost;
import appeng.util.InventoryAdaptor;
import appeng.util.Platform;
import appeng.util.helpers.ItemHandlerUtil;
import appeng.util.inv.InvOperation;
import appeng.util.inv.WrapperChainedItemHandler;
import appeng.util.inv.WrapperFilteredItemHandler;
import appeng.util.inv.filter.IAEItemFilter;
import appeng.util.item.AEItemStack;

public class MolecularAssemblerBlockEntity extends AENetworkInvBlockEntity
        implements IUpgradeableHost, IConfigManagerHost, IGridTickable, ICraftingMachine, IPowerChannelState {

    public static final String INVENTORY_MAIN = "molecular_assembler";

    private final CraftingInventory craftingInv;
    private final AppEngInternalInventory gridInv = new AppEngInternalInventory(this, 9 + 1, 1);
    private final AppEngInternalInventory patternInv = new AppEngInternalInventory(this, 1, 1);
    private final FixedItemInv gridInvExt = new WrapperFilteredItemHandler(this.gridInv, new CraftingGridFilter());
    private final FixedItemInv internalInv = new WrapperChainedItemHandler(this.gridInv, this.patternInv);
    private final IConfigManager settings;
    private final UpgradeInventory upgrades;
    private boolean isPowered = false;
    private AEPartLocation pushDirection = AEPartLocation.INTERNAL;
    private ItemStack myPattern = ItemStack.EMPTY;
    private ICraftingPatternDetails myPlan = null;
    private double progress = 0;
    private boolean isAwake = false;
    private boolean forcePlan = false;
    private boolean reboot = true;

    @Environment(EnvType.CLIENT)
    private AssemblerAnimationStatus animationStatus;

    public MolecularAssemblerBlockEntity(BlockEntityType<?> tileEntityTypeIn) {
        super(tileEntityTypeIn);
        final ITileDefinition assembler = Api.instance().definitions().blocks().molecularAssembler();

        this.settings = new ConfigManager(this);
        this.settings.registerSetting(Settings.REDSTONE_CONTROLLED, RedstoneMode.IGNORE);
        this.getProxy().setIdlePowerUsage(0.0);
        this.upgrades = new DefinitionUpgradeInventory(assembler, this, this.getUpgradeSlots());
        this.craftingInv = new CraftingInventory(new ContainerNull(), 3, 3);

    }

    private int getUpgradeSlots() {
        return 5;
    }

    @Override
    public boolean pushPattern(final ICraftingPatternDetails patternDetails, final CraftingInventory table,
            final Direction where) {
        if (this.myPattern.isEmpty()) {
            boolean isEmpty = ItemHandlerUtil.isEmpty(this.gridInv) && ItemHandlerUtil.isEmpty(this.patternInv);

            if (isEmpty && patternDetails.isCraftable()) {
                this.forcePlan = true;
                this.myPlan = patternDetails;
                this.pushDirection = AEPartLocation.fromFacing(where);

                for (int x = 0; x < table.size(); x++) {
                    this.gridInv.setInvStack(x, table.getStack(x), Simulation.ACTION);
                }

                this.updateSleepiness();
                this.saveChanges();
                return true;
            }
        }
        return false;
    }

    private void updateSleepiness() {
        final boolean wasEnabled = this.isAwake;
        this.isAwake = this.myPlan != null && this.hasMats() || this.canPush();
        if (wasEnabled != this.isAwake) {
            try {
                if (this.isAwake) {
                    this.getProxy().getTick().wakeDevice(this.getProxy().getNode());
                } else {
                    this.getProxy().getTick().sleepDevice(this.getProxy().getNode());
                }
            } catch (final GridAccessException e) {
                // :P
            }
        }
    }

    private boolean canPush() {
        return !this.gridInv.getInvStack(9).isEmpty();
    }

    private boolean hasMats() {
        if (this.myPlan == null) {
            return false;
        }

        for (int x = 0; x < this.craftingInv.size(); x++) {
            this.craftingInv.setStack(x, this.gridInv.getInvStack(x));
        }

        return !this.myPlan.getOutput(this.craftingInv, this.getWorld()).isEmpty();
    }

    @Override
    public boolean acceptsPlans() {
        return ItemHandlerUtil.isEmpty(this.patternInv);
    }

    @Override
    public int getInstalledUpgrades(final Upgrades u) {
        return this.upgrades.getInstalledUpgrades(u);
    }

    @Override
    protected boolean readFromStream(final PacketByteBuf data) throws IOException {
        final boolean c = super.readFromStream(data);
        final boolean oldPower = this.isPowered;
        this.isPowered = data.readBoolean();
        return this.isPowered != oldPower || c;
    }

    @Override
    protected void writeToStream(final PacketByteBuf data) throws IOException {
        super.writeToStream(data);
        data.writeBoolean(this.isPowered);
    }

    @Override
    public CompoundTag toTag(final CompoundTag data) {
        super.toTag(data);
        if (this.forcePlan && this.myPlan != null) {
            final ItemStack pattern = this.myPlan.getPattern();
            if (!pattern.isEmpty()) {
                final CompoundTag compound = new CompoundTag();
                pattern.toTag(compound);
                data.put("myPlan", compound);
                data.putInt("pushDirection", this.pushDirection.ordinal());
            }
        }

        this.upgrades.writeToNBT(data, "upgrades");
        this.settings.writeToNBT(data);
        return data;
    }

    @Override
    public void fromTag(BlockState state, final CompoundTag data) {
        super.fromTag(state, data);
        if (data.contains("myPlan")) {
            final ItemStack myPat = ItemStack.fromTag(data.getCompound("myPlan"));

            if (!myPat.isEmpty() && myPat.getItem() instanceof EncodedPatternItem) {
                final World w = this.getWorld();
                final ICraftingPatternDetails ph = Api.instance().crafting().decodePattern(myPat, w);
                if (ph != null && ph.isCraftable()) {
                    this.forcePlan = true;
                    this.myPlan = ph;
                    this.pushDirection = AEPartLocation.fromOrdinal(data.getInt("pushDirection"));
                }
            }
        }

        this.upgrades.readFromNBT(data, "upgrades");
        this.settings.readFromNBT(data);
        this.recalculatePlan();
    }

    private void recalculatePlan() {
        this.reboot = true;

        if (this.forcePlan) {
            return;
        }

        final ItemStack is = this.patternInv.getInvStack(0);

        if (!is.isEmpty() && is.getItem() instanceof EncodedPatternItem) {
            if (!ItemStack.areItemsEqual(is, this.myPattern)) {
                final World w = this.getWorld();
                final ICraftingPatternDetails ph = Api.instance().crafting().decodePattern(is, w);

                if (ph != null && ph.isCraftable()) {
                    this.progress = 0;
                    this.myPattern = is;
                    this.myPlan = ph;
                }
            }
        } else {
            this.progress = 0;
            this.forcePlan = false;
            this.myPlan = null;
            this.myPattern = ItemStack.EMPTY;
            this.pushDirection = AEPartLocation.INTERNAL;
        }

        this.updateSleepiness();
    }

    @Override
    public AECableType getCableConnectionType(final AEPartLocation dir) {
        return AECableType.COVERED;
    }

    @Override
    public DimensionalCoord getLocation() {
        return new DimensionalCoord(this);
    }

    @Override
    public IConfigManager getConfigManager() {
        return this.settings;
    }

    @Override
    public FixedItemInv getInventoryByName(final String name) {
        if (name.equals("upgrades")) {
            return this.upgrades;
        }

        if (name.equals("molecular_assembler")) {
            return this.internalInv;
        }

        return null;
    }

    @Override
    public void updateSetting(final IConfigManager manager, final Settings settingName, final Enum<?> newValue) {

    }

    @Override
    public FixedItemInv getInternalInventory() {
        return this.internalInv;
    }

    @Override
    protected FixedItemInv getItemHandlerForSide(Direction side) {
        return this.gridInvExt;
    }

    @Override
    public void onChangeInventory(final FixedItemInv inv, final int slot, final InvOperation mc,
            final ItemStack removed, final ItemStack added) {
        if (inv == this.gridInv || inv == this.patternInv) {
            this.recalculatePlan();
        }
    }

    public int getCraftingProgress() {
        return (int) this.progress;
    }

    @Override
    public void getDrops(final World w, final BlockPos pos, final List<ItemStack> drops) {
        super.getDrops(w, pos, drops);

        for (int h = 0; h < this.upgrades.getSlotCount(); h++) {
            final ItemStack is = this.upgrades.getInvStack(h);
            if (!is.isEmpty()) {
                drops.add(is);
            }
        }
    }

    @Override
    public TickingRequest getTickingRequest(final IGridNode node) {
        this.recalculatePlan();
        this.updateSleepiness();
        return new TickingRequest(1, 1, !this.isAwake, false);
    }

    @Override
    public TickRateModulation tickingRequest(final IGridNode node, int ticksSinceLastCall) {
        if (!this.gridInv.getInvStack(9).isEmpty()) {
            this.pushOut(this.gridInv.getInvStack(9));

            // did it eject?
            if (this.gridInv.getInvStack(9).isEmpty()) {
                this.saveChanges();
            }

            this.ejectHeldItems();
            this.updateSleepiness();
            this.progress = 0;
            return this.isAwake ? TickRateModulation.IDLE : TickRateModulation.SLEEP;
        }

        if (this.myPlan == null) {
            this.updateSleepiness();
            return TickRateModulation.SLEEP;
        }

        if (this.reboot) {
            ticksSinceLastCall = 1;
        }

        if (!this.isAwake) {
            return TickRateModulation.SLEEP;
        }

        this.reboot = false;
        int speed = 10;
        switch (this.upgrades.getInstalledUpgrades(Upgrades.SPEED)) {
            case 0:
                this.progress += this.userPower(ticksSinceLastCall, speed = 10, 1.0);
                break;
            case 1:
                this.progress += this.userPower(ticksSinceLastCall, speed = 13, 1.3);
                break;
            case 2:
                this.progress += this.userPower(ticksSinceLastCall, speed = 17, 1.7);
                break;
            case 3:
                this.progress += this.userPower(ticksSinceLastCall, speed = 20, 2.0);
                break;
            case 4:
                this.progress += this.userPower(ticksSinceLastCall, speed = 25, 2.5);
                break;
            case 5:
                this.progress += this.userPower(ticksSinceLastCall, speed = 50, 5.0);
                break;
        }

        if (this.progress >= 100) {
            for (int x = 0; x < this.craftingInv.size(); x++) {
                this.craftingInv.setStack(x, this.gridInv.getInvStack(x));
            }

            this.progress = 0;
            final ItemStack output = this.myPlan.getOutput(this.craftingInv, this.getWorld());
            if (!output.isEmpty()) {
                // FIXME FABRIC BasicEventHooks.firePlayerCraftingEvent((PlayerEntity)
                // FakePlayer.getOrCreate((ServerWorld) this.getWorld()), output,
                // FIXME FABRIC this.craftingInv);

                this.pushOut(output.copy());

                for (int x = 0; x < this.craftingInv.size(); x++) {
                    this.gridInv.setInvStack(x, Platform.getRecipeRemainder(this.craftingInv.getStack(x)),
                            Simulation.ACTION);
                }

                if (ItemHandlerUtil.isEmpty(this.patternInv)) {
                    this.forcePlan = false;
                    this.myPlan = null;
                    this.pushDirection = AEPartLocation.INTERNAL;
                }

                this.ejectHeldItems();

                final IAEItemStack item = AEItemStack.fromItemStack(output);
                if (item != null) {
                    final TargetPoint where = new TargetPoint(this.pos.getX(), this.pos.getY(), this.pos.getZ(), 32,
                            this.world);
                    NetworkHandler.instance()
                            .sendToAllAround(new AssemblerAnimationPacket(this.pos, (byte) speed, item), where);
                }

                this.saveChanges();
                this.updateSleepiness();
                return this.isAwake ? TickRateModulation.IDLE : TickRateModulation.SLEEP;
            }
        }

        return TickRateModulation.FASTER;
    }

    private void ejectHeldItems() {
        if (this.gridInv.getInvStack(9).isEmpty()) {
            for (int x = 0; x < 9; x++) {
                final ItemStack is = this.gridInv.getInvStack(x);
                if (!is.isEmpty()) {
                    if (this.myPlan == null || !this.myPlan.isValidItemForSlot(x, is, this.world)) {
                        this.gridInv.setInvStack(9, is, Simulation.ACTION);
                        this.gridInv.setInvStack(x, ItemStack.EMPTY, Simulation.ACTION);
                        this.saveChanges();
                        return;
                    }
                }
            }
        }
    }

    private int userPower(final int ticksPassed, final int bonusValue, final double acceleratorTax) {
        try {
            return (int) (this.getProxy().getEnergy().extractAEPower(ticksPassed * bonusValue * acceleratorTax,
                    Actionable.MODULATE, PowerMultiplier.CONFIG) / acceleratorTax);
        } catch (final GridAccessException e) {
            return 0;
        }
    }

    private void pushOut(ItemStack output) {
        if (this.pushDirection == AEPartLocation.INTERNAL) {
            for (final Direction d : Direction.values()) {
                output = this.pushTo(output, d);
            }
        } else {
            output = this.pushTo(output, this.pushDirection.getFacing());
        }

        if (output.isEmpty() && this.forcePlan) {
            this.forcePlan = false;
            this.recalculatePlan();
        }

        this.gridInv.setInvStack(9, output, Simulation.ACTION);
    }

    private ItemStack pushTo(ItemStack output, final Direction d) {
        if (output.isEmpty()) {
            return output;
        }

        final BlockEntity te = this.getWorld().getBlockEntity(this.pos.offset(d));

        if (te == null) {
            return output;
        }

        final InventoryAdaptor adaptor = InventoryAdaptor.getAdaptor(te, d.getOpposite());

        if (adaptor == null) {
            return output;
        }

        final int size = output.getCount();
        output = adaptor.addItems(output);
        final int newSize = output.isEmpty() ? 0 : output.getCount();

        if (size != newSize) {
            this.saveChanges();
        }

        return output;
    }

    @MENetworkEventSubscribe
    public void onPowerEvent(final MENetworkPowerStatusChange p) {
        this.updatePowerState();
    }

    private void updatePowerState() {
        boolean newState = false;

        try {
            newState = this.getProxy().isActive() && this.getProxy().getEnergy().extractAEPower(1, Actionable.SIMULATE,
                    PowerMultiplier.CONFIG) > 0.0001;
        } catch (final GridAccessException ignored) {

        }

        if (newState != this.isPowered) {
            this.isPowered = newState;
            this.markForUpdate();
        }
    }

    @Override
    public boolean isPowered() {
        return this.isPowered;
    }

    @Override
    public boolean isActive() {
        return this.isPowered;
    }

    @Environment(EnvType.CLIENT)
    public void setAnimationStatus(@Nullable AssemblerAnimationStatus status) {
        this.animationStatus = status;
    }

    @Environment(EnvType.CLIENT)
    @Nullable
    public AssemblerAnimationStatus getAnimationStatus() {
        return this.animationStatus;
    }

    private class CraftingGridFilter implements IAEItemFilter {
        private boolean hasPattern() {
            return MolecularAssemblerBlockEntity.this.myPlan != null
                    && !ItemHandlerUtil.isEmpty(MolecularAssemblerBlockEntity.this.patternInv);
        }

        @Override
        public boolean allowExtract(FixedItemInv inv, int slot, int amount) {
            return slot == 9;
        }

        @Override
        public boolean allowInsert(FixedItemInv inv, int slot, ItemStack stack) {
            if (slot >= 9) {
                return false;
            }

            if (this.hasPattern()) {
                return MolecularAssemblerBlockEntity.this.myPlan.isValidItemForSlot(slot, stack,
                        MolecularAssemblerBlockEntity.this.getWorld());
            }
            return false;
        }
    }
}
