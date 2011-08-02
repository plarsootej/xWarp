package de.xzise.xwarp;

import java.util.List;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;

import com.google.common.collect.Lists;

import de.xzise.MinecraftUtil;
import de.xzise.xwarp.WarpManager.WarpObjectGetter;
import de.xzise.xwarp.dataconnections.DataConnection;
import de.xzise.xwarp.list.PersonalList;

public abstract class CommonManager<T extends DefaultWarpObject<?>, L extends PersonalList<T, ?>> implements Manager<T> {

    protected final L list;
    protected final PluginProperties properties;
    protected final String typeName;
    
    protected CommonManager(L list, String typeName, PluginProperties properties) {
        this.properties = properties;
        this.typeName = typeName;
        this.list = list;
        this.list.setIgnoreCase(!properties.isCaseSensitive());
    }

    @SuppressWarnings("unchecked")
    public void blindAdd(boolean database, List<T> warpObjects) {
        this.blindAdd(database, (T[]) warpObjects.toArray());
    }
    
    public void blindAdd(List<T> warpObjects) {
        this.blindAdd(false, warpObjects);
    }

    public void blindAdd(T... warpObjects) {
        this.blindAdd(false, warpObjects);
    }

    public void blindAdd(boolean database, T... warpObjects) {
        for (T warpObject : warpObjects) {
            this.list.addWarpObject(warpObject);
        }
        if (database) {
            this.blindDataAdd(warpObjects);
        }
        // if (this.getWarp(warp.name) == null) {
        // this.global.put(warp.name.toLowerCase(), warp);
        // } else if (warp.visibility == Visibility.GLOBAL) {
        // throw new
        // IllegalArgumentException("A global warp could not override an existing one.");
        // }
        // if (!putIntoPersonal(personal, warp)) {
        // throw new
        // IllegalArgumentException("A personal warp could not override an existing one.");
        // }
    }
    
    protected abstract void blindDataAdd(T... warpObjects);

    @Override
    public boolean isNameAvailable(T warpObject) {
        return this.isNameAvailable(warpObject.getName(), warpObject.getOwner());
    }

    @Override
    public boolean isNameAvailable(String name, String owner) {
        return this.list.getWarpObject(name, owner, null) == null;
    }

    @Override
    public T getWarpObject(String name, String owner, String playerName) {
        return this.list.getWarpObject(name, owner, playerName);
    }

    @Override
    public void reload() {
        this.list.setIgnoreCase(!properties.isCaseSensitive());
    }

    @SuppressWarnings("unchecked")
    @Override
    public T[] getWarpObjects() {
        return (T[]) this.list.getWarpObjects().toArray();
    }

    @Override
    public void importWarpObjects(DataConnection connection, WarpObjectGetter<T> getter, CommandSender sender) {
        List<T> warps = getter.get();
        List<T> allowedWarps = Lists.newArrayListWithExpectedSize(warps.size());
        List<T> notAllowedWarps = Lists.newArrayListWithExpectedSize(warps.size());
        for (T warp : warps) {
            if (this.isNameAvailable(warp)) {
                allowedWarps.add(warp);
            } else {
                notAllowedWarps.add(warp);
            }
        }

        for (T warp : allowedWarps) {
            warp.assignNewId();
        }

        if (allowedWarps.size() > 0) {
            this.blindAdd(true, allowedWarps);
            sender.sendMessage("Imported " + ChatColor.GREEN + allowedWarps.size() + ChatColor.WHITE + " " + this.typeName + " into the database.");
        }

        if (notAllowedWarps.size() > 0) {
            sender.sendMessage(ChatColor.RED + "Found " + notAllowedWarps.size() + " " + this.typeName + " which cause naming conflicts.");
            // Max lines - 1 (for the header) - 1 (for the succeed message)
            if (notAllowedWarps.size() < MinecraftUtil.getMaximumLines(sender) - 1) {
                for (T warp : notAllowedWarps) {
                    sender.sendMessage(ChatColor.GREEN + warp.getName() + ChatColor.WHITE + " by " + ChatColor.GREEN + warp.getOwner());
                }
            }
        }
    }
    

    @Override
    public int getSize() {
        return this.list.getSize(null);
    }
}