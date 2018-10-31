import java.util.ArrayList
import java.util.Iterator
import java.util.List
import java.util.Map.Entry

import com.branegy.inventory.api.InventoryService
import com.branegy.inventory.model.Database
import com.branegy.service.core.QueryRequest
import com.branegy.service.base.api.ProjectService
import com.branegy.service.connection.api.ConnectionService
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
import com.branegy.service.connection.model.DatabaseConnection;

final InventoryService inventorySrv = dbm.getService(InventoryService.class)

class UnderfinedRow{
    final List<Contact> contacts = [];
    final Map<String,List<Job>> envJobs = [:];
    final Map<String,List<Database>> envDatabases = [:];
    final Map<String,List<Server>> envServers = [:];
}

class AppNameRow{
    final Map<String,List<Database>> envDatabases = [:];
    final List<ContactLink> contactLinks = [];
    final Map<String,List<Job>> envJobs = [:];
}

Map<String,AppNameRow> data = new TreeMap(String.CASE_INSENSITIVE_ORDER);
UnderfinedRow undefined;

// applications
inventorySrv.getApplicationList(new QueryRequest(p_app_filter)).each{
   data.putIfAbsent(it.applicationName, new AppNameRow());
}

// contactLinks
def contacts = dbm.getService(ContactService.class).getContactList(new QueryRequest(p_contact_filter)).collectEntries{[(it.contactName):it]};
new ArrayList(dbm.getService(ContactLinkService.class).findAllByClass(Application.class,null)).sort{it.contact.contactName}.each{
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
def servers = inventorySrv.getServerList(new QueryRequest()).sort{it.serverName}.collectEntries{[(it.serverName): it]};
def connections = dbm.getService(ConnectionService.class).getConnectionList().collectEntries{[(it.name): it]};

def getDatabaseServerKey = {database -> return database.connectionName+"=>"+database.databaseName}
def getEnvironmentByConnection =  { connectionName -> return connections.get(connectionName)?.getCustomData("Environment")}
def getEnvironmentByServer =  { serverName -> return connections.get(serverName)?.getCustomData("Environment")}
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

def databases = inventorySrv.getDatabaseList(new QueryRequest(p_db_filter)).sort{getDatabaseServerKey(it)}.collectEntries{[(getDatabaseServerKey(it)): it]};

def usedDatabases = [] as Set;
def usedServers = [] as Set;
inventorySrv.getDBUsageList().each{
    def env = getEnvironmentByDatabase(it.database);
    data.get(it.application.applicationName).envDatabases.put(env,it.database);
    environments << env;
    usedDatabases << getDatabaseServerKey(it.database);
    usedServers << it.database.connectionName;
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
        usedServers << it.connectionName;
    }
}
usedServers.clear();
usedDatabases.clear();
databases.clear();


// jobs
def getJobKey = {job -> return job.serverName+"=>"+job.jobType+"=>"+job.jobName; };
def jobs = inventorySrv.getJobList(new QueryRequest(p_job_filter)).sort{getJobKey(it)};
def jobApp = new ArrayList(inventorySrv.findApplicationLinkListByObjectClass(Job.class))
        .sort{getJobKey(it.job)}.collectEntries{[(getJobKey(it.job)): it.application]};
jobs.each{ job->
    def jobKey = getJobKey(job);
    def envJob = getEnvironmentByJob(job);
    def app = jobApp.get(jobKey);
    if (app!=null) {
        data.get(app.applicationName).envJobs.put(envJob,job)
    } else {
        if (undefined == null) {
            undefined = new UnderfinedRow();
        }
        undefined.envJobs.computeIfAbsent(envJob,{k-> new ArrayList()}) << job;
        environments << envJob;
    }
};
jobs.clear();
jobApp.clear();



// servers
servers.keySet().removeAll(usedServers);
if (!servers.isEmpty()) {
    if (undefined == null) {
        undefined = new UnderfinedRow();
    }
    servers.values().each{
        def env = getEnvironmentByServer(it.serverName);
        undefined.envServers.computeIfAbsent(env,{k->new ArrayList()}) << it;
        environments << env;
    }
}
servers.clear();

connections.clear();

// sort environment
environments = new ArrayList(environments).sort();

//============================================================================================================
logger.info( " Env list = ${ environments.join(" ")}")

def emptystr(obj) {
    return obj==null ? "" : obj;
}

def undefinedColumn = environments.contains(null);

println """<table class="simple-table" cellspacing="0" cellpadding="10">
           <tr style="background-color:#EEE">
             <td>Application</td>
             <td>Contacts</td>
             ${environments.collect{ "<td>${it==null?"&lt;undefined&gt;":it}</td>" }.join("")}
           </tr>"""

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
            undefined.envServers.get(env).each{ server->
                def link = "#inventory/project:${toURL(projectName)}/servers/server:${toURL(server.serverName)}/installations"
                println "<a href=\"${link}\">${server.serverName}</a><br/>"
            }
        }
        if (undefined.envDatabases.containsKey(env)) {
            println "Databases<br/>";
            undefined.envDatabases.get(env).each{ db->
                def link = "#inventory/project:${toURL(projectName)}/databases/connection:${toURL(db.connectionName)},db:${toURL(db.databaseName)}/applications"
                println "<a href=\"${link}\">${db.connectionName}.${db.databaseName}</a><br/>"
            }
        }
        if (undefined.envJobs.containsKey(env)) {
            println "Jobs<br/>";
            undefined.envJobs.get(env).each{ job->
                def link = "#inventory/project:${toURL(projectName)}/jobs/job:${toURL(job.jobName)},server:${toURL(job.serverName)},type:${toURL(job.jobType)}/applications"
                println "<a href=\"${link}\">${job.serverName}.${job.jobName} [${job.jobType}]</a><br/>"
            }
        }
        println "</td>"
    }
    
    println "</tr>"
}

data.each {
    println "<tr style=\"vertical-align: top;\">"
    
    println "<td><a href=\"#inventory/project:${toURL(projectName)}/applications/application:${toURL(it.key)}/databases\">${it.key}</a></td>"
    
    println "<td>"
    it.value.contactLinks.each{    
        def link = "#inventory/project:${toURL(projectName)}/contacts/contact:${toURL(it.contact.contactName)}"
        println "<a href=\"${link}\">${it.contact.contactName}</a> ${emptystr(it.getCustomData("ContactRole"))}<br/>"
    }
    println "</td>"
    
    def envDatabases = it.value.envDatabases;
    def envJobs = it.value.envJobs;
    environments.each { env ->
        println "<td style=\"padding:5px\">"
        if (envDatabases.containsKey(env)) {
           println "Databases<br/>";
           envDatabases.get(env).each{ db->
               def link = "#inventory/project:${toURL(projectName)}/databases/connection:${toURL(db.connectionName)},db:${toURL(db.databaseName)}/applications"
               println "<a href=\"${link}\">${db.connectionName}.${db.databaseName}</a><br/>"
           }
        }
        if (envJobs.containsKey(env)) {
            println "Jobs<br/>";
            envJobs.get(env).each{ job->
                def link = "#inventory/project:${toURL(projectName)}/jobs/job:${toURL(job.jobName)},server:${toURL(job.serverName)},type:${toURL(job.jobType)}/applications"
                println "<a href=\"${link}\">${job.serverName}.${job.jobName} [${job.jobType}]</a><br/>"
            }
        }
        println "</td>"
        
    }
    println "</tr>"
}
println "</table>"
