package mirefresh.mir.machine;

import mirefresh.mir.menu.MachineMenu;
import mirefresh.mir.recipe.ElectricMachineRecipe;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.entity.BlockEntityType;

import java.util.function.Supplier;

/**
 * The definition of one kind of machine (electric furnace, compressor, ...). Adding a machine to
 * MI-Refresh means creating one {@code MachineType} and registering the block / block entity /
 * menu / recipe type it points at. Everything downstream (block, BE, menu, screen) is generic and
 * driven by this record.
 */
public record MachineType(
        String name,
        MachineTier tier,
        int inputSlots,
        int outputSlots,
        Supplier<BlockEntityType<MachineBlockEntity>> blockEntityType,
        Supplier<RecipeType<ElectricMachineRecipe>> recipeType,
        Supplier<MenuType<MachineMenu>> menuType
) {
    public int totalSlots() {
        return inputSlots + outputSlots;
    }
}
