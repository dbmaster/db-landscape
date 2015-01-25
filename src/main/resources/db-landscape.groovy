import java.util.ArrayList
import java.util.Iterator
import java.util.List
import java.util.Map.Entry

import com.branegy.inventory.api.InventoryService
import com.branegy.inventory.model.Database
import com.branegy.service.core.QueryRequest
import com.branegy.service.base.api.ProjectService


def emptystr(obj) {
    return obj==null ? "" : obj;
}


StringBuilder importLog = new StringBuilder(10000);

InventoryService inventorySrv = dbm.getService(InventoryService.class);

inventoryDBs = new ArrayList(inventorySrv.getDatabaseList(new QueryRequest(p_db_filter)))

inventoryDBs.sort { it.getServerName()+"_"+it.getDatabaseName()  }

def db2AppsLinks = inventorySrv.getDBUsageList();
dbApps = db2AppsLinks.groupBy { it.getDatabase() }

// fields = p_fields == null ? [] : p_fields.split(";")

def environments = [] as Set

def dbGrid = new TreeMap()

def mapDbToApp =  { databaseGrid, appName, database, environment ->
    def dbByEnv = databaseGrid[appName] 
    if (dbByEnv == null ) { 
        dbByEnv = [:] 
        databaseGrid[appName] = dbByEnv
    }
    def dbList = dbByEnv[environment] 
    if (dbList == null) {
        dbList = []
        dbByEnv[environment]  = dbList
    }
    dbList << database
}

def appList = null;
if (p_app_filter!=null) { 
    appList = inventorySrv.getApplicationList(new QueryRequest(p_app_filter))
}


for (Database database: inventoryDBs) {
    if (!database.isDeleted()) {
        def environment = database.getCustomData("Environment") ?: "Undefined"

        def apps = dbApps[database];
        
        if (apps!=null && apps.size() >0 ) {
            apps.each { 
                def appName = it.getApplication()?.getApplicationName() ?: "Undefined"

                if (appList == null || appList.find { app  -> app.getApplicationName().equals(appName) } !=null) { 
                    environments << environment
                    mapDbToApp (dbGrid, appName, database, environment )
                }
            }
        } else if (appList == null) {
            environments << environment
            mapDbToApp (dbGrid, "UnAssigned", database , environment )
       }
    }
}


environments = environments.sort { it }

println """<table class="simple-table" cellspacing="0" cellpadding="10">
           <tr style="background-color:#EEE">
             <td>Application</td>
             ${environments.collect { "<td>${it}</td>" }.join("")}
           </tr>"""

def toURL = { link -> link.encodeURL().replaceAll("\\+", "%20") }
String.metaClass.encodeURL = { java.net.URLEncoder.encode(delegate) }

String projectName =  dbm.getService(ProjectService.class).getCurrentProject().getName()

dbGrid.each {
    println "<tr style=\"vertical-align: top;\"><td>${it.key}</td>"
    environments.each { env ->
        dbList = it.value[env]
        println "<td style=\"padding:5px\">"
        if (dbList!=null) {
            dbList.each { db ->
                def link = "#inventory/project:${toURL(projectName)}/databases/server:${toURL(db.getServerName())},db:${toURL(db.getDatabaseName())}/applications"
                println "<a href=\"${link}\">${db.getServerName()}.${db.getDatabaseName()}</a><br/>"
            }
        }
        println "</td>"
    }
    println "</tr>"
}

println "</table>"