import java.util.ArrayList
import java.util.Iterator
import java.util.List
import java.util.Map.Entry

import com.branegy.inventory.api.InventoryService
import com.branegy.inventory.model.Database
import com.branegy.service.core.QueryRequest
import com.branegy.service.base.api.ProjectService
import com.branegy.service.connection.api.ConnectionService
import com.branegy.dbmaster.custom.CustomFieldConfig
import com.branegy.dbmaster.custom.field.server.api.ICustomFieldService
import com.branegy.inventory.api.ContactLinkService
import com.branegy.inventory.api.ContactService
import com.branegy.inventory.model.Application
import com.branegy.inventory.model.ApplicationLink
import com.branegy.inventory.model.DatabaseUsage
import com.branegy.inventory.model.Contact
import com.branegy.inventory.model.ContactLink
import com.branegy.inventory.model.Job
import com.branegy.inventory.model.Server
import com.branegy.service.connection.model.DatabaseConnection
import com.branegy.cfg.IPropertySupplier;


final InventoryService inventorySrv = dbm.getService(InventoryService.class)

class UnderfinedRow{
    final List<Contact> contacts = [];
    final Map<String,Map<String,List<Job>>> envJobs = [:]; // env -> jobType <-> Job
    final Map<String,List<Database>> envDatabases = [:];
    final Map<String,List<Server>> envServers = [:];
}

class AppNameRow{
    final Map<String,List<Database>> envDatabases = [:];
    final List<ContactLink> contactLinks = [];
    final Map<String,Map<String,List<Job>>> envJobs = [:];
    final Map<String,List<Server>> envServers = [:];
}

Map<String,AppNameRow> data = new TreeMap(String.CASE_INSENSITIVE_ORDER);
UnderfinedRow undefined;

// applications
inventorySrv.getApplicationList(new QueryRequest(/*p_app_filter*/)).each{
   data.computeIfAbsent(it.applicationName,{k-> new AppNameRow()});
}

// contactLinks
def contacts = dbm.getService(ContactService.class).getContactList(new QueryRequest(/*p_contact_filter*/)).collectEntries{[(it.contactName):it]};
dbm.getService(ContactLinkService.class).findAllByClass(Application.class,null).each{
    data.get(it.application.applicationName).contactLinks << it;
    contacts.remove(it.contact.contactName);
};
if (!contacts.isEmpty()) {
    if (undefined == null) {
        undefined = new UnderfinedRow();
    }
    undefined.contacts.addAll(contacts.values());
}
contacts.clear();

// environments + server + database 
def environments = [] as Set

def connections = dbm.getService(ConnectionService.class).getConnectionList().collectEntries{[(it.name): it]};

def getDatabaseServerKey = {database -> return database.connectionName+"=>"+database.databaseName}
def getEnvironmentByConnection =  { connectionName -> return connections.get(connectionName)?.getCustomData("Environment")}
def getEnvironmentByJob = {job ->
    def env = job.getCustomData("Environment");
    if (env == null) {
        env = getEnvironmentByConnection(job.serverName);
    }
    return env;
};
def getEnvironmentByDatabase = {obj ->
    def env = obj.getCustomData("Environment");
    if (env == null) {
        env = getEnvironmentByConnection(obj.connectionName);
    }
    return env;
};


def ignoreDatabase = {database -> return ["master","msdb","tempdb","model"].contains(database.databaseName)};

def databases = inventorySrv.getDatabaseList(new QueryRequest(/*p_db_filter*/)).findAll{!ignoreDatabase(it)}.collectEntries{[(getDatabaseServerKey(it)): it]};
def usedDatabases = [] as Set;
def usedConnections = [] as Set;
inventorySrv.getDBUsageList().each{
    if (!ignoreDatabase(it.database)) {
        def env = getEnvironmentByDatabase(it.database);
        data.get(it.application.applicationName).envDatabases.computeIfAbsent(env,{k->new ArrayList()}) << it.database;
        environments << env;
        usedDatabases << getDatabaseServerKey(it.database);
        usedConnections << it.database.connectionName;
    }
}

databases.keySet().removeAll(usedDatabases);
if (!databases.isEmpty()) {
    if (undefined == null) {
        undefined = new UnderfinedRow();
    }
    databases.values().each{
        def env = getEnvironmentByDatabase(it);
        undefined.envDatabases.computeIfAbsent(env,{k-> new ArrayList()}) << it;
        environments << env;
        usedConnections << it.connectionName;
    }
}
usedConnections.clear();
usedDatabases.clear();
databases.clear();


// jobs
def getJobKey = {job -> return job.serverName+"=>"+job.jobType+"=>"+job.jobName; };
def jobs = inventorySrv.getJobList(new QueryRequest(/*p_job_filter*/));
def jobApp = inventorySrv.findApplicationLinkListByObjectClass(Job.class)
        .collectEntries{[(getJobKey(it.job)): it.application]};
jobs.each{ job ->
    def jobKey = getJobKey(job);
    def envJob = getEnvironmentByJob(job);
    def app = jobApp.get(jobKey);
    if (app!=null) {
        data.get(app.applicationName).envJobs.computeIfAbsent(envJob,{k->new LinkedHashMap()}).computeIfAbsent(job.jobType,{k->new ArrayList()}) << job
    } else {
        if (undefined == null) {
            undefined = new UnderfinedRow();
        }
        undefined.envJobs.computeIfAbsent(envJob,{k-> new LinkedHashMap()}).computeIfAbsent(job.jobType,{k->new ArrayList()}) << job;
        environments << envJob;
    }
};
jobs.clear();
jobApp.clear();


// servers
def getEnvironmentByServer =  { server -> return server.getCustomData("Environment")}
def servers = inventorySrv.getServerList(new QueryRequest(/*p_server_filter*/)).collectEntries{[(it.serverName): it]};
inventorySrv.getInstallationList().each{ installation ->
    def env = getEnvironmentByServer(installation.server);
    data.get(installation.application.applicationName).envServers.computeIfAbsent(env,{k->new ArrayList()}) << installation.server;
    environments << env;
    servers.remove(installation.server.serverName);
}
if (!servers.isEmpty()) {
    if (undefined == null) {
        undefined = new UnderfinedRow();
    }
    servers.values().each{
        def env = getEnvironmentByServer(it);
        undefined.envServers.computeIfAbsent(env,{k->new ArrayList()}) << it;
        environments << env;
    }
}
servers.clear();
connections.clear();

// sort environment
CustomFieldConfig config = dbm.getService(ICustomFieldService.class).getConfigByName("Connection","Environment");
def sortedEnvironments = new LinkedHashSet();
if (environments.contains(null)) {
    sortedEnvironments << null;
}
config?.textValues?.each{ v ->
    if (environments.contains(v)) {
        sortedEnvironments << v;
    }        
}
sortedEnvironments.addAll(environments);
environments = sortedEnvironments;

//============================================================================================================
logger.info( " Env list = ${ environments.join(" ")}")

def emptystr(obj) {
    return obj==null ? "" : obj;
}

def jobComparator = {Job a,Job b -> 
    def result = a.serverName <=> b.serverName;
    if (result == 0) {
        result = a.jobType <=> b.jobType;
        if (result == 0) {
            result = a.jobName <=> b.jobName;
        }
    }
    return result;
} as Comparator;
def serverComparator = {Server a,Server b -> 
    return a.serverName <=> b.serverName;
} as Comparator;
def databaseComparator = {Database a,Database b -> 
    def result = a.connectionName <=> b.connectionName; 
    if (result == 0) {
        result = a.databaseName <=> b.databaseName;
    }
    return result;
} as Comparator;
def contactLinkComparator = {ContactLink a, ContactLink b ->
    return a.contact.contactName <=> b.contact.contactName;
} as Comparator;


def undefinedColumn = environments.contains(null);

println """<script src="https://ajax.googleapis.com/ajax/libs/jquery/3.3.1/jquery.min.js" defer></script>"""
println """<script src="https://cdnjs.cloudflare.com/ajax/libs/floatthead/2.0.3/jquery.floatThead.min.js" defer></script>"""
def inlineScriptBase64 = """
\$(function(){
   \$('table.simple-table').floatThead();
});
""".bytes.encodeBase64();
println """<script src="data:text/javascript;base64,${inlineScriptBase64}" defer></script>"""

println """<table class="simple-table" cellspacing="0" cellpadding="10">
           <thead>
           <tr style="background-color:#EEE">
             <th>Application</th>
             <th>Contacts</th>
             ${environments.collect{ "<th>${it==null?"&lt;undefined&gt;":it}</th>" }.join("")}
           </tr></thead><tbody>"""

def toURL = { link -> link.encodeURL().replaceAll("\\+", "%20") }
String.metaClass.encodeURL = { java.net.URLEncoder.encode(delegate) }

String projectName =  dbm.getService(ProjectService.class).getCurrentProject().getName()

if (undefined!=null) {
    println "<tr style=\"vertical-align: top;\">"
    println "<td>&lt;undefined&gt;</td>"
    println "<td>"
    /*undefined.contacts.each{
        def link = "#inventory/project:${toURL(projectName)}/contacts/contact:${toURL(it.contactName)}"
        println "<a href=\"${link}\">${it.contactName}</a><br/>"
    }*/
    println "</td>"
    environments.each{ env ->
        println "<td style=\"padding:5px\">"
        if (undefined.envServers.containsKey(env)) {
            println "Server<br/>";
            def list = undefined.envServers.get(env);
            list.sort(serverComparator);
            list.each{ server->
                def link = "#inventory/project:${toURL(projectName)}/servers/server:${toURL(server.serverName)}/installations"
                println "<a href=\"${link}\">${server.serverName}</a><br/>"
            }
        }
        if (undefined.envDatabases.containsKey(env)) {
            println "Databases<br/>";
            
            def list = undefined.envDatabases.get(env);
            list.sort(databaseComparator);
            list.each{ db->
                def link = "#inventory/project:${toURL(projectName)}/databases/connection:${toURL(db.connectionName)},db:${toURL(db.databaseName)}/applications"
                println "<a href=\"${link}\">${db.connectionName}.${db.databaseName}</a><br/>"
            }
        }
        if (undefined.envJobs.containsKey(env)) {
            undefined.envJobs.get(env).entrySet().each{ e ->
                println e.key+" jobs<br/>";
                def list = e.value;
                list.sort(jobComparator);
                list.each{  job->
                    def link = "#inventory/project:${toURL(projectName)}/jobs/job:${toURL(job.jobName)},server:${toURL(job.serverName)},type:${toURL(job.jobType)}/applications"
                    println "<a href=\"${link}\">${job.serverName}.${job.jobName}</a><br/>"
                }
            }
        }
        println "</td>"
    }
    
    println "</tr>"
}

def globalProperties = dbm.getService(IPropertySupplier.class)
def roleField = globalProperties.getProperty("contract_role.role.field_name","ContactRole")
logger.debug("Will be using field ${roleField}")


data.each {
    println "<tr style=\"vertical-align: top;\">"
    
    println "<td><a href=\"#inventory/project:${toURL(projectName)}/applications/application:${toURL(it.key)}/databases\">${it.key}</a></td>"
    
    println "<td>"
    it.value.contactLinks.sort(contactLinkComparator).each{ cl ->   
        def link = "#inventory/project:${toURL(projectName)}/applications/application:${toURL(it.key)}/contacts"
        println "<a href=\"${link}\">${cl.contact.contactName}</a> ${emptystr(cl.getCustomData(roleField))}<br/>"
    }
    println "</td>"
    
    def envDatabases = it.value.envDatabases;
    def envJobs = it.value.envJobs;
    def envServers = it.value.envServers;
    environments.each { env ->
        println "<td style=\"padding:5px\">"
        if (envServers.containsKey(env)) {
            println "Server<br/>";
            def list = envServers.get(env);
            list.sort(serverComparator);
            list.each{ server->
                def link = "#inventory/project:${toURL(projectName)}/servers/server:${toURL(server.serverName)}/installations"
                println "<a href=\"${link}\">${server.serverName}</a><br/>"
            }
        }
        if (envDatabases.containsKey(env)) {
           println "Databases<br/>";
           def list = envDatabases.get(env);
           list.sort(databaseComparator);
           list.each{ db->
               def link = "#inventory/project:${toURL(projectName)}/databases/connection:${toURL(db.connectionName)},db:${toURL(db.databaseName)}/applications"
               println "<a href=\"${link}\">${db.connectionName}.${db.databaseName}</a><br/>"
           }
        }
        if (envJobs.containsKey(env)) {
            envJobs.get(env).entrySet().each{e ->
                println e.key+" jobs<br/>";
                def list = e.value;
                list.sort(jobComparator); 
                list.each{job->
                    def link = "#inventory/project:${toURL(projectName)}/jobs/job:${toURL(job.jobName)},server:${toURL(job.serverName)},type:${toURL(job.jobType)}/applications"
                    println "<a href=\"${link}\">${job.serverName}.${job.jobName}</a><br/>"
                }
            }
        }
        println "</td>"
        
    }
    println "</tr>"
}
println "</tbody></table>"
