package com.on.ebx.factory.accel;

/** Where a pack asks a block to be added: a table and optionally the group name (default: the block's own). */
public final class BlockPlacement
{
	private final String block;
	private final String table;
	private final String group;

	BlockPlacement(final String pBlock, final String pTable, final String pGroup)
	{
		this.block = pBlock;
		this.table = pTable;
		this.group = pGroup;
	}

	public String block()
	{
		return this.block;
	}

	public String table()
	{
		return this.table;
	}

	/** The group name asked for, or null for the block's default. */
	public String group()
	{
		return this.group;
	}
}
