/*
 * This file is part of Applied Energistics 2.
 * Copyright (c) 2021, TeamAppliedEnergistics, All rights reserved.
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

package appeng.fluids.container;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.container.ContainerType;
import net.minecraft.network.PacketBuffer;

import appeng.api.config.RedstoneMode;
import appeng.api.config.SecurityPermissions;
import appeng.api.config.Settings;
import appeng.container.ContainerLocator;
import appeng.container.implementations.ContainerHelper;
import appeng.core.sync.network.NetworkHandler;
import appeng.core.sync.packets.ConfigValuePacket;
import appeng.fluids.parts.FluidLevelEmitterPart;
import appeng.fluids.util.IAEFluidTank;

public class FluidLevelEmitterContainer extends FluidConfigurableContainer {
    public static ContainerType<FluidLevelEmitterContainer> TYPE;

    private static final ContainerHelper<FluidLevelEmitterContainer, FluidLevelEmitterPart> helper = new ContainerHelper<>(
            FluidLevelEmitterContainer::new, FluidLevelEmitterPart.class, SecurityPermissions.BUILD);

    public static FluidLevelEmitterContainer fromNetwork(int windowId, PlayerInventory inv, PacketBuffer buf) {
        return helper.fromNetwork(windowId, inv, buf, (host, container, buffer) -> {
            container.reportingValue = buffer.readVarLong();
        });
    }

    public static boolean open(PlayerEntity player, ContainerLocator locator) {
        return helper.open(player, locator, (host, buffer) -> {
            buffer.writeVarLong(host.getReportingValue());
        });
    }

    private final FluidLevelEmitterPart lvlEmitter;

    // Only synced once on container-open, and only used on client
    private long reportingValue;

    public FluidLevelEmitterContainer(int id, final PlayerInventory ip, final FluidLevelEmitterPart te) {
        super(TYPE, id, ip, te);
        this.lvlEmitter = te;
    }

    public long getReportingValue() {
        return reportingValue;
    }

    public void setReportingValue(long reportingValue) {
        if (isClient()) {
            if (reportingValue != this.reportingValue) {
                this.reportingValue = reportingValue;
                NetworkHandler.instance()
                        .sendToServer(new ConfigValuePacket("FluidLevelEmitter.Value", String.valueOf(reportingValue)));
            }
        } else {
            this.lvlEmitter.setReportingValue(reportingValue);
        }
    }

    @Override
    protected void setupConfig() {
    }

    @Override
    protected boolean supportCapacity() {
        return false;
    }

    @Override
    public int availableUpgrades() {

        return 0;
    }

    @Override
    public void detectAndSendChanges() {
        this.verifyPermissions(SecurityPermissions.BUILD, false);

        if (isServer()) {
            this.setRedStoneMode(
                    (RedstoneMode) this.getUpgradeable().getConfigManager().getSetting(Settings.REDSTONE_EMITTER));
        }

        this.standardDetectAndSendChanges();
    }

    @Override
    public IAEFluidTank getFluidConfigInventory() {
        return this.lvlEmitter.getConfig();
    }
}
