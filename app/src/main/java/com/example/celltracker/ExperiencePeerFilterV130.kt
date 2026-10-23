package com.example.celltracker

/** Only Wi-Fi Direct service advertisements intended for CellTracker may appear in discovery. */
internal object ExperiencePeerFilterV130 {
    fun matchesService(instance:String,type:String):Boolean =
        instance=="CellTrackerController" && type.startsWith("_celltracker._tcp",ignoreCase=true)

    fun matchesTxt(domain:String,fields:Map<String,String>):Boolean =
        domain.contains("_celltracker._tcp",ignoreCase=true) &&
            fields["app"]=="CellTracker" && fields["purpose"]=="posttest-report-sync" &&
            fields["version"]=="1"
}
