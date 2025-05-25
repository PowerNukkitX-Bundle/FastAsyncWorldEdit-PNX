/*
 * WorldEdit, a Minecraft world manipulation toolkit
 * Copyright (C) sk89q <http://www.sk89q.com>
 * Copyright (C) WorldEdit team and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.sk89q.worldedit.pnx.registry;

import cn.nukkit.network.protocol.types.biome.BiomeDefinition;
import cn.nukkit.network.protocol.types.biome.BiomeDefinitionData;
import cn.nukkit.registry.Registries;
import com.sk89q.worldedit.pnx.PNXAdapter;
import com.sk89q.worldedit.util.formatting.text.Component;
import com.sk89q.worldedit.util.formatting.text.TranslatableComponent;
import com.sk89q.worldedit.util.translation.TranslationManager;
import com.sk89q.worldedit.world.biome.BiomeData;
import com.sk89q.worldedit.world.biome.BiomeType;
import com.sk89q.worldedit.world.registry.BiomeRegistry;

import javax.annotation.Nullable;

/**
 * A biome registry for Bukkit.
 */
class PNXBiomeRegistry implements BiomeRegistry {

    PNXBiomeRegistry() {
    }

    @Override
    public Component getRichName(BiomeType biomeType) {
        return TranslatableComponent.of(
                TranslationManager.makeTranslationKey("biome", biomeType.getId())
        );
    }

    @Deprecated
    @Nullable
    @Override
    public BiomeData getData(BiomeType biome) {
        final int adapt = PNXAdapter.adapt(biome);
        final BiomeDefinition biomeDefinition = Registries.BIOME.get(adapt);
        return biomeDefinition == null ? null : biomeDefinition::getName;
    }

}
