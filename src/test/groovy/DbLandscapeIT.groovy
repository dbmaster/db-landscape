import static org.testng.Assert.assertTrue;

import org.testng.annotations.Test

import io.dbmaster.testng.BaseToolTestNGCase;


public class DbLandscapeIT extends BaseToolTestNGCase {

    @Test
    public void test() {
        def parameters = [ : ]
        parameters.put("p_exlcude_undefined":false);
        parameters.put("p_include_objects":["Servers","Databases","Jobs","SecurityObjects","Contacts"]);
        
        String result = tools.toolExecutor("db-landscape", parameters).execute()
        assertTrue(result.contains("Application"), "Unexpected search results ${result}");
        assertTrue(result.contains("Undefined"), "Unexpected search results ${result}");
    }
}
