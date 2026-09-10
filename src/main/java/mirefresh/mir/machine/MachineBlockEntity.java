package mirefresh.mir.machine;

import mirefresh.mir.Config;
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
import org.patryk3211.powergrid.electricity.sim.ElectricWire;

/**
 * Generic electric machine block entity.
 *
 * <p>Electrically it is a two-terminal load: a single resistor whose value is re-computed every
 * tick as {@code V^2 / tier.maxWatts} (a "constant power" load, low-pass filtered for solver
 * stability, clamped to [minR, maxR]). The power PowerGrid's solver pushes through that resistor
 * ({@code loadWire.power()}, watts) is integrated into a joule buffer; recipes spend joules from
 * the buffer to make progress. A weak grid -> low voltage -> less power -> slower crafting.
 */
public class MachineBlockEntity extends ElectricBlockEntity implements MenuProvider {

    private final MachineType machineType;
    private final ItemStackHandler inventory;

    @Nullable
    private ElectricWire loadWire;

    private double jouleBuffer;
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
        builder.setTerminalCount(2);
        double maxR = Config.MACHINE_MAX_RESISTANCE.get();
        this.loadWire = builder.connect((float) maxR, builder.terminalNode(0), builder.terminalNode(1));
    }

    // ---------------------------------------------------------------- tick

    @Override
    public void electricalTick() {
        if (level == null || level.isClientSide) return;

        final double dt = 0.05; // one game tick, seconds. loadWire.power() is already tick-averaged.
        final double maxW = machineType.tier().maxWatts;
        final double idleR = Config.MACHINE_MAX_RESISTANCE.get();
        // never present less than 1/4 of nominal resistance (would draw ~4x rated at design voltage)
        final double floorR = Math.max(Config.MACHINE_MIN_RESISTANCE.get(), machineType.tier().nominalResistance() * 0.25);
        // buffer only smooths solver jitter: ~0.2s of headroom, so a de-powered machine stops within a few ticks
        final double bufferCap = maxW * dt * 4.0;

        double power = loadWire != null ? Math.max(0.0, loadWire.power()) : 0.0;
        double voltage = loadWire != null ? Math.abs(loadWire.potentialDifference()) : 0.0;
        // a machine can only ingest up to ~1.5x its rating; a monster grid does not make it faster
        double usablePower = Math.min(power, maxW * 1.5);
        jouleBuffer = Math.min(jouleBuffer + usablePower * dt, bufferCap);
        lastWatts = (float) usablePower;
        lastVoltage = (float) voltage;

        // Re-evaluate the recipe if the input slot changed, or if the cached recipe no longer
        // matches what is actually in the input slot (e.g. a hopper pulled the item mid-craft).
        if (recipeDirty
                || (currentRecipe != null
                    && !currentRecipe.matches(new SingleRecipeInput(inventory.getStackInSlot(0)), level))) {
            refreshRecipe();
            recipeDirty = false;
        }

        boolean running = currentRecipe != null && hasOutputRoom(currentRecipe);

        // Constant-power load model, low-passed for stability. Running: aim for a resistance that
        // draws maxW at the measured voltage, clamped to [floorR, idleR]. Idle: present idleR so
        // the machine barely loads the grid.
        if (loadWire != null) {
            double targetR = (running && voltage > 1.0e-3)
                    ? Mth.clamp(voltage * voltage / maxW, floorR, idleR)
                    : idleR;
            double newR = Mth.clamp(loadWire.getResistance() * 0.6 + targetR * 0.4, floorR, idleR);
            if (newR != loadWire.getResistance()) loadWire.setResistance(newR);
        }

        if (running) {
            double take = Math.min(jouleBuffer, Math.min(maxW * dt, recipeEnergyRemaining));
            if (take > 0) {
                jouleBuffer -= take;
                recipeEnergyRemaining -= take;
            }
            ticksThisRecipe++;
            if (recipeEnergyRemaining <= 0 && ticksThisRecipe >= currentRecipe.minDuration()) {
                finishRecipe();
            }
        } else {
            jouleBuffer = Math.max(0.0, jouleBuffer - maxW * dt); // bleed off fast when idle
        }

        // TEMP diagnostics (remove after debugging): dump state every 2s.
        if (++diagTick % 40 == 0) {
            mirefresh.mir.Mir.LOGGER.info(
                "[mir/diag] {} wire={} net={} R={} V={} P={} recipe={} running={} buf={} rem={}",
                getBlockPos(),
                loadWire != null,
                loadWire != null && loadWire.getNetwork() != null,
                loadWire != null ? String.format("%.2f", loadWire.getResistance()) : "-",
                String.format("%.3f", voltage),
                String.format("%.3f", power),
                currentRecipe != null ? currentRecipe.getResultItem(level.registryAccess()) : "none",
                running, String.format("%.1f", jouleBuffer), String.format("%.1f", recipeEnergyRemaining));
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
        jouleBuffer = tag.getDouble("JouleBuffer");
        recipeEnergyRemaining = tag.getDouble("RecipeEnergyRemaining");
        ticksThisRecipe = tag.getInt("TicksThisRecipe");
        recipeDirty = true;
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        tag.put("Inventory", inventory.serializeNBT(registries));
        tag.putDouble("JouleBuffer", jouleBuffer);
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
