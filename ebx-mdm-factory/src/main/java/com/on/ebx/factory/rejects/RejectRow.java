package com.on.ebx.factory.rejects;

import java.util.Date;

/** One reject as the review screen and the CSV export show it (a plain value object, filled from a SQL tuple). */
public final class RejectRow
{
	public String entityId = "";
	public String entityName = "";
	public String stage = "";
	public String sourceSystem = "";
	public String sourceId = "";
	public String landingTable = "";
	public String loadId = "";
	public String reasons = "";
	/** Staging load rejects (2026-10-17): the cause category, the grouping signature and EBX's raw text ({@code ""} on a row written before they existed). */
	public String category = "";
	public String reasonCode = "";
	public String detail = "";
	public Date rejectedAt;
	public boolean resolved;
	public boolean acknowledged;
	public String reviewedBy = "";
	public Date reviewedAt;
	public String reviewComment = "";

	public String status()
	{
		return RejectFilter.statusOf(Boolean.valueOf(this.resolved), Boolean.valueOf(this.acknowledged));
	}

	public RejectRef ref()
	{
		return new RejectRef(this.entityId, this.stage, this.sourceSystem, this.sourceId);
	}
}
