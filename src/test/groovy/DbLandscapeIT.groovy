import static org.testng.Assert.assertTrue;

import org.testng.annotations.Test

import io.dbmaster.testng.BaseToolTestNGCase;


public class DbLandscapeIT extends BaseToolTestNGCase {

    @Test
    public void test() {
        def parameters = [ : ]
        parameters.put("p_exlcude_undefined", Boolean.FALSE);
        parameters.put("p_include_objects",["Servers","Databases","Jobs","SecurityObjects","Contacts"] as String[]);
        
        String result = tools.toolExecutor("db-landscape", parameters).execute().toLowerCase();
        assertTrue(result.contains("application"), "Unexpected search results ${result}");
        assertTrue(result.contains("undefined"), "Unexpected search results ${result}");
    }
}
