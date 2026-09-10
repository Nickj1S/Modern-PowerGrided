package mirefresh.mir.machine;

import mirefresh.mir.Config;
import mirefresh.mir.electric.ElectricLoad;
import mirefresh.mir.menu.MachineMenu;
import mirefresh.mir.recipe.ElectricMachineRecipe;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.jetbrains.annotations.Nullable;
import org.patryk3211.powergrid.electricity.base.ElectricBlockEntity;
import org.patryk3211.powergrid.electricity.base.IElectricEntity;

/**
 * Generic electric machine block entity. The electrical behaviour lives in {@link ElectricLoad}
 * (shared with the planned MI connector); this class just drives it with "do I have a recipe to
 * run" and spends the resulting joules on recipe progress.
 */
public class MachineBlockEntity extends ElectricBlockEntity implements MenuProvider {

    private final MachineType machineType;
    private final ItemStackHandler inventory;
    private final ElectricLoad load = new ElectricLoad();

    private double recipeEnergyRemaining;
    private int ticksThisRecipe;

    @Nullable
    private ElectricMachineRecipe currentRecipe;
    private boolean recipeDirty = true;

    // Sampled each tick for the screen.
    private float lastWatts;
    private float lastVoltage;

    private final ContainerData data = new ContainerData() {
        @Override
        public int get(int i) {
            return switch (i) {
                case 0 -> currentRecipe != null && currentRecipe.energy() > 0
                        ? Mth.clamp((int) Math.round((1.0 - recipeEnergyRemaining / currentRecipe.energy()) * 1000.0), 0, 1000)
                        : 0;
                case 1 -> Math.round(lastWatts);
                case 2 -> Math.round(lastVoltage);
                case 3 -> (int) Math.round(machineType.tier().maxWatts);
                default -> 0;
            };
        }

        @Override
        public void set(int i, int v) { }

        @Override
        public int getCount() {
            return 4;
        }
    };

    public MachineBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state, MachineType machineType) {
        super(type, pos, state);
        this.machineType = machineType;
        this.inventory = new ItemStackHandler(machineType.totalSlots()) {
            @Override
            protected void onContentsChanged(int slot) {
                setChanged();
                if (slot < machineType.inputSlots()) recipeDirty = true;
            }

            @Override
            public boolean isItemValid(int slot, ItemStack stack) {
                return slot < machineType.inputSlots(); // only input slots take items from outside
            }
        };
    }

    public MachineType machineType() {
        return machineType;
    }

    public ItemStackHandler inventory() {
        return inventory;
    }

    public ContainerData containerData() {
        return data;
    }

    // ---------------------------------------------------------------- circuit

    @Override
    public void buildCircuit(IElectricEntity.CircuitBuilder builder) {
        load.attach(builder, Config.MACHINE_MAX_RESISTANCE.get());
    }

    @Override
    public void electricalTick() {
        if (level == null || level.isClientSide) return;

        if (recipeDirty
                || (currentRecipe != null
                    && !currentRecipe.matches(new SingleRecipeInput(inventory.getStackInSlot(0)), level))) {
            refreshRecipe();
            recipeDirty = false;
        }

        boolean running = currentRecipe != null && hasOutputRoom(currentRecipe);
        double maxW = machineType.tier().maxWatts;

        load.serverTick(running, maxW, machineType.tier().nominalResistance(),
                Config.MACHINE_MAX_RESISTANCE.get(), Config.MACHINE_MIN_RESISTANCE.get());
        lastWatts = load.watts();
        lastVoltage = load.voltage();

        if (running) {
            double take = load.drawJoules(Math.min(maxW * 0.05, recipeEnergyRemaining));
            recipeEnergyRemaining -= take;
            ticksThisRecipe++;
            if (recipeEnergyRemaining <= 0 && ticksThisRecipe >= currentRecipe.minDuration()) {
                finishRecipe();
            }
        }

        if (++diagTick % 40 == 0) { // TEMP diagnostics — remove once the MI bridge is in
            mirefresh.mir.Mir.LOGGER.info("[mir/diag] {} V={} W={} buf={} recipe={} running={} rem={}",
                    getBlockPos(), String.format("%.1f", lastVoltage), String.format("%.1f", lastWatts),
                    String.format("%.1f", load.bufferedJoules()),
                    currentRecipe != null ? currentRecipe.getResultItem(level.registryAccess()) : "none",
                    running, String.format("%.1f", recipeEnergyRemaining));
        }

        setChanged();
    }

    private int diagTick;

    // ---------------------------------------------------------------- recipe

    private void refreshRecipe() {
        if (level == null) return;
        ItemStack in = inventory.getStackInSlot(0);
        if (in.isEmpty()) {
            currentRecipe = null;
            recipeEnergyRemaining = 0;
            ticksThisRecipe = 0;
            return;
        }
        RecipeHolder<ElectricMachineRecipe> match = level.getRecipeManager()
                .getRecipeFor(machineType.recipeType().get(), new SingleRecipeInput(in), level)
                .orElse(null);
        ElectricMachineRecipe recipe = match != null ? match.value() : null;
        if (recipe != currentRecipe) {
            currentRecipe = recipe;
            recipeEnergyRemaining = recipe != null ? recipe.energy() : 0;
            ticksThisRecipe = 0;
        }
    }

    private boolean hasOutputRoom(ElectricMachineRecipe recipe) {
        ItemStack result = recipe.getResultItem(level.registryAccess());
        ItemStack out = inventory.getStackInSlot(machineType.inputSlots());
        if (out.isEmpty()) return true;
        return ItemStack.isSameItemSameComponents(out, result)
                && out.getCount() + result.getCount() <= out.getMaxStackSize();
    }

    private void finishRecipe() {
        if (inventory.getStackInSlot(0).isEmpty()) { // input vanished mid-craft: abort, no dupe
            currentRecipe = null;
            recipeEnergyRemaining = 0;
            ticksThisRecipe = 0;
            recipeDirty = true;
            return;
        }
        ItemStack result = currentRecipe.getResultItem(level.registryAccess()).copy();
        inventory.extractItem(0, 1, false);
        int outSlot = machineType.inputSlots();
        ItemStack out = inventory.getStackInSlot(outSlot);
        if (out.isEmpty()) {
            inventory.setStackInSlot(outSlot, result);
        } else {
            out.grow(result.getCount());
        }
        currentRecipe = null;
        recipeEnergyRemaining = 0;
        ticksThisRecipe = 0;
        recipeDirty = true;
    }

    // ---------------------------------------------------------------- nbt

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        if (tag.contains("Inventory")) inventory.deserializeNBT(registries, tag.getCompound("Inventory"));
        load.load(tag);
        recipeEnergyRemaining = tag.getDouble("RecipeEnergyRemaining");
        ticksThisRecipe = tag.getInt("TicksThisRecipe");
        recipeDirty = true;
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        tag.put("Inventory", inventory.serializeNBT(registries));
        load.save(tag);
        tag.putDouble("RecipeEnergyRemaining", recipeEnergyRemaining);
        tag.putInt("TicksThisRecipe", ticksThisRecipe);
    }

    // ---------------------------------------------------------------- menu

    @Override
    public Component getDisplayName() {
        return Component.translatable(getBlockState().getBlock().getDescriptionId());
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int id, Inventory playerInventory, Player player) {
        return MachineMenu.forServer(id, playerInventory, this);
    }
}
