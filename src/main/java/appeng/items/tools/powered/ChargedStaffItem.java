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

package appeng.items.tools.powered;

import net.minecraft.entity.LivingEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.DamageSource;
import net.minecraft.util.math.AxisAlignedBB;

import appeng.api.config.Actionable;
import appeng.core.AEConfig;
import appeng.core.AppEng;
import appeng.core.sync.packets.LightningPacket;
import appeng.items.tools.powered.powersink.AEBasePoweredItem;
import appeng.util.Platform;

public class ChargedStaffItem extends AEBasePoweredItem {

    public ChargedStaffItem(Item.Properties props) {
        super(AEConfig.instance().getChargedStaffBattery(), props);
    }

    @Override
    public boolean hitEntity(final ItemStack item, final LivingEntity target, final LivingEntity hitter) {
        if (this.getAECurrentPower(item) > 300) {
            this.extractAEPower(item, 300, Actionable.MODULATE);
            if (!target.world.isRemote()) {
                for (int x = 0; x < 2; x++) {
                    final AxisAlignedBB entityBoundingBox = target.getBoundingBox();
                    final float dx = (float) (Platform.getRandomFloat() * target.getWidth() + entityBoundingBox.minX);
                    final float dy = (float) (Platform.getRandomFloat() * target.getHeight() + entityBoundingBox.minY);
                    final float dz = (float) (Platform.getRandomFloat() * target.getWidth() + entityBoundingBox.minZ);
                    AppEng.proxy.sendToAllNearExcept(null, dx, dy, dz, 32.0, target.world,
                            new LightningPacket(dx, dy, dz));
                }
            }
            target.attackEntityFrom(DamageSource.MAGIC, 6);
            return true;
        }

        return false;
    }
}
