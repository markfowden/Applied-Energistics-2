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

package appeng.client.gui.widgets;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.matrix.MatrixStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.util.text.ITextComponent;

/**
 * Displays a small icon that shows validation errors for some input control.
 */
public class ValidationIcon extends IconButton {

    private final List<ITextComponent> tooltip = new ArrayList<>();

    private boolean valid;

    public ValidationIcon(int x, int y) {
        super(x, y, btn -> {
        });
        setDisableBackground(true);
        setDisableClickSound(true);
        setHalfSize(true);
    }

    public void setValid(boolean valid) {
        setVisibility(!valid);
        if (valid) {
            this.tooltip.clear();
        }
    }

    public void setTooltip(List<ITextComponent> lines) {
        this.tooltip.clear();
        this.tooltip.addAll(lines);
    }

    @Override
    protected int getIconIndex() {
        return 16 * 8;
    }

    @Override
    public boolean changeFocus(boolean flag) {
        return false; // Cannot focus this element
    }

    @Override
    public void renderToolTip(MatrixStack matrices, int mouseX, int mouseY) {
        if (this.tooltip.isEmpty()) {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        Screen screen = client.currentScreen;
        if (screen == null) {
            return;
        }

        screen.func_243308_b(matrices, this.tooltip, x, y);
    }
}
