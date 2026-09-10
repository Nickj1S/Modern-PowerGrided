package mirefresh.mir.menu;

import mirefresh.mir.machine.MachineBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.items.SlotItemHandler;
import org.jetbrains.annotations.Nullable;

/**
 * Menu for any {@link MachineBlockEntity}. Slot layout is furnace-like: input(s) on the left,
 * output(s) on the right, standard player inventory below. Progress / watts / voltage / max-watts
 * are synced through a 4-int {@link ContainerData}.
 */
public class MachineMenu extends AbstractContainerMenu {

    private final ContainerLevelAccess access;
    private final ContainerData data;
    @Nullable
    private final MachineBlockEntity blockEntity;
    private final int inputSlots;

    /** Server-side constructor. */
    public MachineMenu(MenuType<MachineMenu> type, int id, Inventory playerInv, MachineBlockEntity be) {
        super(type, id);
        this.blockEntity = be;
        this.inputSlots = be.machineType().inputSlots();
        this.access = ContainerLevelAccess.create(be.getLevel(), be.getBlockPos());
        this.data = be.containerData();
        addMachineSlots(be.inventory(), be.machineType().inputSlots(), be.machineType().outputSlots());
        addPlayerInventory(playerInv);
        addDataSlots(data);
    }

    /** Client-side constructor (from network). */
    private MachineMenu(MenuType<MachineMenu> type, int id, Inventory playerInv, int inputSlots, int outputSlots) {
        super(type, id);
        this.blockEntity = null;
        this.inputSlots = inputSlots;
        this.access = ContainerLevelAccess.NULL;
        this.data = new SimpleContainerData(4);
        addMachineSlots(new ItemStackHandler(inputSlots + outputSlots), inputSlots, outputSlots);
        addPlayerInventory(playerInv);
        addDataSlots(data);
    }

    public static MachineMenu fromNetwork(int id, Inventory playerInv, RegistryFriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        if (playerInv.player.level().getBlockEntity(pos) instanceof MachineBlockEntity be) {
            return new MachineMenu(be.machineType().menuType().get(), id, playerInv, be);
        }
        // Fallback: a bare 1-in/1-out menu so the client does not crash on a desync.
        @SuppressWarnings("unchecked")
        MenuType<MachineMenu> anyType = (MenuType<MachineMenu>) playerInv.player.containerMenu.getType();
        return new MachineMenu(anyType, id, playerInv, 1, 1);
    }

    private void addMachineSlots(IItemHandler handler, int in, int out) {
        // one input column at x=48, one output column at x=112, vertically centred around y=35
        for (int i = 0; i < in; i++) {
            addSlot(new SlotItemHandler(handler, i, 48, 35 - (in - 1) * 9 + i * 18));
        }
        for (int i = 0; i < out; i++) {
            addSlot(new SlotItemHandler(handler, in + i, 112, 35 - (out - 1) * 9 + i * 18) {
                @Override
                public boolean mayPlace(ItemStack stack) {
                    return false;
                }
            });
        }
    }

    private void addPlayerInventory(Inventory inv) {
        for (int row = 0; row < 3; row++)
            for (int col = 0; col < 9; col++)
                addSlot(new Slot(inv, col + row * 9 + 9, 8 + col * 18, 84 + row * 18));
        for (int col = 0; col < 9; col++)
            addSlot(new Slot(inv, col, 8 + col * 18, 142));
    }

    public int machineSlotCount() {
        return containerSlotCount();
    }

    private int containerSlotCount() {
        return this.slots.size() - 36;
    }

    public float progress01() {
        return data.get(0) / 1000f;
    }

    public int watts() {
        return data.get(1);
    }

    public int voltage() {
        return data.get(2);
    }

    public int maxWatts() {
        return Math.max(1, data.get(3));
    }

    @Override
    public boolean stillValid(Player player) {
        return access.evaluate((level, pos) -> level.getBlockEntity(pos) instanceof MachineBlockEntity, true);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack result = ItemStack.EMPTY;
        Slot slot = slots.get(index);
        if (slot != null && slot.hasItem()) {
            ItemStack stack = slot.getItem();
            result = stack.copy();
            int machineSlots = containerSlotCount();
            if (index < machineSlots) {
                if (!moveItemStackTo(stack, machineSlots, slots.size(), true)) return ItemStack.EMPTY;
            } else {
                if (!moveItemStackTo(stack, 0, inputSlots, false)) return ItemStack.EMPTY;
            }
            if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
            else slot.setChanged();
        }
        return result;
    }
}
