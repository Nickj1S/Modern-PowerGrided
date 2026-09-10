package mirefresh.mir;

import mirefresh.mir.machine.MachineBlock;
import mirefresh.mir.machine.MachineBlockEntity;
import mirefresh.mir.machine.MachineTier;
import mirefresh.mir.machine.MachineType;
import mirefresh.mir.menu.MachineMenu;
import mirefresh.mir.recipe.ElectricMachineRecipe;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;

/**
 * Central registry of MI-Refresh content. One machine == one {@link MachineType} plus the four
 * registry objects it points at (recipe type, recipe serializer, block entity type, menu type)
 * and a block/item pair. v0.1 ships a single machine: the LV Electric Furnace.
 */
public final class Registration {

    // ---- Electric Furnace : recipe type + serializer ------------------------------------------

    public static final DeferredHolder<RecipeType<?>, RecipeType<ElectricMachineRecipe>> ELECTRIC_FURNACE_RECIPE_TYPE =
            Mir.RECIPE_TYPES.register("electric_furnace", () -> ElectricMachineRecipe.newType("electric_furnace"));

    public static final DeferredHolder<RecipeSerializer<?>, ElectricMachineRecipe.Serializer> ELECTRIC_FURNACE_SERIALIZER =
            Mir.RECIPE_SERIALIZERS.register("electric_furnace",
                    () -> new ElectricMachineRecipe.Serializer(ELECTRIC_FURNACE_RECIPE_TYPE::get));

    // ---- Electric Furnace : machine definition ------------------------------------------------
    // Cross-references to registry holders declared below go through the static accessor methods
    // at the bottom of this class to dodge Java's field forward-reference rules.

    public static final MachineType ELECTRIC_FURNACE_TYPE = new MachineType(
            "electric_furnace",
            MachineTier.LV,
            1, 1,
            Registration::electricFurnaceBe,
            Registration::electricFurnaceRecipeType,
            Registration::electricFurnaceMenu
    );

    // ---- Electric Furnace : block / item / block entity / menu ------------------------------

    public static final DeferredBlock<MachineBlock> ELECTRIC_FURNACE = Mir.BLOCKS.register(
            "electric_furnace",
            () -> new MachineBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.METAL)
                            .instrument(NoteBlockInstrument.IRON_XYLOPHONE)
                            .requiresCorrectToolForDrops()
                            .strength(4.0f)
                            .noOcclusion(),
                    ELECTRIC_FURNACE_TYPE));

    public static final DeferredItem<BlockItem> ELECTRIC_FURNACE_ITEM =
            Mir.ITEMS.registerSimpleBlockItem("electric_furnace", ELECTRIC_FURNACE);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MachineBlockEntity>> ELECTRIC_FURNACE_BE =
            Mir.BLOCK_ENTITIES.register("electric_furnace",
                    () -> BlockEntityType.Builder.<MachineBlockEntity>of(
                            Registration::makeElectricFurnaceBe, ELECTRIC_FURNACE.get()
                    ).build(null));

    public static final DeferredHolder<MenuType<?>, MenuType<MachineMenu>> ELECTRIC_FURNACE_MENU =
            Mir.MENUS.register("electric_furnace",
                    () -> IMenuTypeExtension.create(MachineMenu::fromNetwork));

    // ---- MI bridge: hidden companion block entity type (never placed in the world) -----------

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<mirefresh.mir.mi.MiElectricCompanion>> MI_COMPANION_BE =
            Mir.BLOCK_ENTITIES.register("mi_companion",
                    () -> BlockEntityType.Builder.<mirefresh.mir.mi.MiElectricCompanion>of(
                            Registration::makeMiCompanion
                    ).build(null));

    private static mirefresh.mir.mi.MiElectricCompanion makeMiCompanion(BlockPos pos, BlockState state) {
        return new mirefresh.mir.mi.MiElectricCompanion(MI_COMPANION_BE.get(), pos, state);
    }

    // ---- accessors (method bodies are exempt from forward-reference restrictions) -----------

    private static MachineBlockEntity makeElectricFurnaceBe(BlockPos pos, BlockState state) {
        return new MachineBlockEntity(ELECTRIC_FURNACE_BE.get(), pos, state, ELECTRIC_FURNACE_TYPE);
    }

    public static BlockEntityType<MachineBlockEntity> electricFurnaceBe() {
        return ELECTRIC_FURNACE_BE.get();
    }

    public static MenuType<MachineMenu> electricFurnaceMenu() {
        return ELECTRIC_FURNACE_MENU.get();
    }

    public static RecipeType<ElectricMachineRecipe> electricFurnaceRecipeType() {
        return ELECTRIC_FURNACE_RECIPE_TYPE.get();
    }

    private Registration() {}

    /** Force class-load so the static {@code register(...)} calls above run. */
    public static void init() {}
}
