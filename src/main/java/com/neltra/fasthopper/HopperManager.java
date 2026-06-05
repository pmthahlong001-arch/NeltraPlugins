package com.neltra.fasthopper;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.minecart.HopperMinecart;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HopperManager - Quản lý logic fast hopper đầy đủ
 * - Hút item từ container BÊN TRÊN
 * - Hút item entity (item drop) phía trên
 * - Đẩy item vào container BÊN DƯỚI hoặc theo HƯỚNG hopper
 * - Hỗ trợ: Chest, Barrel, Hopper, Furnace, Dropper, Dispenser, Shulker Box, v.v.
 * Folia 1.21.11 - RegionScheduler thread-safe
 */
public class HopperManager {

    private final NeltraFastHopper plugin;
    private final Map<String, ScheduledTask> activeTasks = new ConcurrentHashMap<>();

    public HopperManager(NeltraFastHopper plugin) {
        this.plugin = plugin;
    }

    // ─────────────────────────────────────────────
    //  REGISTER / UNREGISTER
    // ─────────────────────────────────────────────

    public void registerHopper(Location loc) {
        if (!plugin.getConfigManager().isEnabled()) return;
        String key = locationKey(loc);
        if (activeTasks.containsKey(key)) return;

        World world = loc.getWorld();
        if (world == null) return;

        int tickSpeed = plugin.getConfigManager().getHopperTickSpeed();

        ScheduledTask task = plugin.getServer().getRegionScheduler().runAtFixedRate(
                plugin, loc,
                scheduledTask -> {
                    Block block = loc.getBlock();
                    if (block.getType() != Material.HOPPER) {
                        unregisterHopper(loc);
                        return;
                    }
                    if (!plugin.getConfigManager().isEnabled()) return;
                    tickHopper(block);
                },
                1L, tickSpeed
        );

        activeTasks.put(key, task);

        if (plugin.getConfigManager().isDebug())
            plugin.getLogger().info("[Debug] Registered hopper @ " + key + " tick=" + tickSpeed);
    }

    public void unregisterHopper(Location loc) {
        String key = locationKey(loc);
        ScheduledTask task = activeTasks.remove(key);
        if (task != null) {
            task.cancel();
            if (plugin.getConfigManager().isDebug())
                plugin.getLogger().info("[Debug] Unregistered hopper @ " + key);
        }
    }

    // ─────────────────────────────────────────────
    //  MAIN TICK LOGIC
    // ─────────────────────────────────────────────

    /**
     * Mỗi tick: hopper làm 2 việc theo đúng thứ tự vanilla
     * 1. PUSH  - đẩy item từ inventory hopper → output
     * 2. PULL  - hút item từ input → vào inventory hopper
     */
    private void tickHopper(Block hopperBlock) {
        if (!(hopperBlock.getState() instanceof org.bukkit.block.Hopper hopperState)) return;
        Inventory hopperInv = hopperState.getInventory();
        int amount = plugin.getConfigManager().getItemsPerTransfer();

        // 1. PUSH: đẩy item ra ngoài trước
        pushItems(hopperBlock, hopperInv, amount);

        // 2. PULL: hút item vào
        pullItems(hopperBlock, hopperInv, amount);
    }

    // ─────────────────────────────────────────────
    //  PUSH - đẩy item từ hopper → output container
    // ─────────────────────────────────────────────

    private void pushItems(Block hopperBlock, Inventory hopperInv, int amount) {
        if (isEmpty(hopperInv)) return;

        // Lấy hướng output của hopper (facing)
        Inventory outputInv = getOutputInventory(hopperBlock);
        if (outputInv == null) return;

        transferItems(hopperInv, outputInv, amount, hopperBlock.getLocation(), "PUSH");
    }

    /**
     * Lấy inventory output của hopper theo hướng facing
     * Hopper có thể quay xuống hoặc sang 4 hướng ngang
     */
    private Inventory getOutputInventory(Block hopperBlock) {
        BlockFace facing = BlockFace.DOWN; // mặc định xuống

        if (hopperBlock.getBlockData() instanceof Directional directional) {
            facing = directional.getFacing();
        }

        Block target = hopperBlock.getRelative(facing);
        return getContainerInventory(target);
    }

    // ─────────────────────────────────────────────
    //  PULL - hút item vào hopper từ input
    // ─────────────────────────────────────────────

    private void pullItems(Block hopperBlock, Inventory hopperInv, int amount) {
        if (isFull(hopperInv)) return;

        // Hút từ container BÊN TRÊN
        Block above = hopperBlock.getRelative(BlockFace.UP);
        Inventory sourceInv = getContainerInventory(above);

        if (sourceInv != null) {
            transferItems(sourceInv, hopperInv, amount, hopperBlock.getLocation(), "PULL-block");
            return;
        }

        // Nếu không có container trên → hút item entity (item drop) trong vùng phía trên
        pullItemEntities(hopperBlock, hopperInv, amount);
    }

    /**
     * Hút item entity (vật phẩm rơi trên đất) vào hopper
     */
    private void pullItemEntities(Block hopperBlock, Inventory hopperInv, int amount) {
        Location center = hopperBlock.getLocation().add(0.5, 1.0, 0.5);
        List<Entity> nearby = new ArrayList<>(
                hopperBlock.getWorld().getNearbyEntities(center, 0.5, 0.5, 0.5)
        );

        int pulled = 0;
        for (Entity entity : nearby) {
            if (pulled >= amount) break;
            if (!(entity instanceof Item itemEntity)) continue;
            if (itemEntity.isDead()) continue;

            ItemStack stack = itemEntity.getItemStack();
            int canTake = Math.min(stack.getAmount(), amount - pulled);

            ItemStack toAdd = stack.clone();
            toAdd.setAmount(canTake);

            Map<Integer, ItemStack> leftover = hopperInv.addItem(toAdd);
            int added = canTake - (leftover.isEmpty() ? 0 : leftover.get(0).getAmount());

            if (added > 0) {
                pulled += added;
                if (stack.getAmount() - added <= 0) {
                    itemEntity.remove();
                } else {
                    stack.setAmount(stack.getAmount() - added);
                    itemEntity.setItemStack(stack);
                }

                if (plugin.getConfigManager().isDebug())
                    plugin.getLogger().info("[Debug] PULL-entity " + added + "x " + toAdd.getType()
                            + " @ " + locationKey(hopperBlock.getLocation()));
            }
        }
    }

    // ─────────────────────────────────────────────
    //  CORE TRANSFER HELPER
    // ─────────────────────────────────────────────

    /**
     * Chuyển item từ source → dest, tối đa amount item
     */
    private void transferItems(Inventory source, Inventory dest, int amount, Location debugLoc, String mode) {
        int moved = 0;

        for (int i = 0; i < source.getSize() && moved < amount; i++) {
            ItemStack item = source.getItem(i);
            if (item == null || item.getType().isAir()) continue;

            ItemStack toMove = item.clone();
            toMove.setAmount(Math.min(item.getAmount(), amount - moved));

            Map<Integer, ItemStack> leftover = dest.addItem(toMove);
            int movedAmount = toMove.getAmount() - (leftover.isEmpty() ? 0 : leftover.get(0).getAmount());

            if (movedAmount > 0) {
                item.setAmount(item.getAmount() - movedAmount);
                source.setItem(i, item.getAmount() <= 0 ? null : item);
                moved += movedAmount;

                if (plugin.getConfigManager().isDebug())
                    plugin.getLogger().info("[Debug] " + mode + " " + movedAmount + "x "
                            + toMove.getType() + " @ " + locationKey(debugLoc));
            }
        }
    }

    // ─────────────────────────────────────────────
    //  INVENTORY HELPERS
    // ─────────────────────────────────────────────

    /**
     * Lấy inventory của một block - hỗ trợ tất cả container
     * Chest đôi, Barrel, Furnace (fuel/result/smelting slot), Hopper,
     * Dropper, Dispenser, Shulker Box, Brewing Stand, Blast Furnace, Smoker, v.v.
     */
    private Inventory getContainerInventory(Block block) {
        if (block == null) return null;
        org.bukkit.block.BlockState state = block.getState();
        if (state instanceof org.bukkit.inventory.InventoryHolder holder) {
            return holder.getInventory();
        }
        return null;
    }

    private boolean isEmpty(Inventory inv) {
        for (ItemStack item : inv.getContents()) {
            if (item != null && !item.getType().isAir()) return false;
        }
        return true;
    }

    private boolean isFull(Inventory inv) {
        for (ItemStack item : inv.getContents()) {
            if (item == null || item.getType().isAir()) return false;
            if (item.getAmount() < item.getMaxStackSize()) return false;
        }
        return true;
    }

    private String locationKey(Location loc) {
        return loc.getWorld().getName() + ":" + loc.getBlockX() + ":" + loc.getBlockY() + ":" + loc.getBlockZ();
    }

    // ─────────────────────────────────────────────
    //  LIFECYCLE
    // ─────────────────────────────────────────────

    public void reload() {
        shutdown();
        plugin.getLogger().info("[NeltraFastHopper] Reloaded. Tick=" + plugin.getConfigManager().getHopperTickSpeed());
    }

    public void shutdown() {
        for (ScheduledTask task : activeTasks.values()) task.cancel();
        activeTasks.clear();
    }

    public int getActiveTaskCount() {
        return activeTasks.size();
    }
}
