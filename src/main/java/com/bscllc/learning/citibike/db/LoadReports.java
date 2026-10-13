package com.bscllc.learning.citibike.db;

import java.sql.*;
import java.time.Instant;
import java.util.LinkedHashMap;

/** Must be called on the same read transaction as the corresponding statistics. */
public final class LoadReports {
    private LoadReports() {}
    public static LoadReport read(Connection c) throws SQLException {
        var rows=new LinkedHashMap<String,Long>();
        try(var s=c.createStatement();var r=s.executeQuery("SELECT provenance,rows FROM source_csv ORDER BY provenance")) {
            while(r.next())rows.put(r.getString(1),r.getLong(2));
        }
        try(var s=c.createStatement();var r=s.executeQuery("SELECT sum(rows),min(minimum_started),max(maximum_started),max(committed_at) FROM active_partitions")) {
            r.next();if(r.getTimestamp(2)==null)throw new DatabaseBusyException("No committed monthly data is available");
            return new LoadReport(rows.size(),rows.size(),r.getLong(1),rows,r.getTimestamp(2).toLocalDateTime(),
                r.getTimestamp(3).toLocalDateTime(),r.getTimestamp(4).toInstant());
        }
    }
}
