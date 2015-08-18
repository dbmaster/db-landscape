import io.dbmaster.testng.BaseToolTestNGCase;

import static org.testng.Assert.assertTrue;
import org.testng.annotations.Test

import com.branegy.tools.api.ExportType;


public class DbLandscapeIT extends BaseToolTestNGCase {

    @Test
    public void test() {
        def parameters = [ : ]
        String result = tools.toolExecutor("db-landscape", parameters).execute()
        assertTrue(result.contains("Application"), "Unexpected search results ${result}");
        assertTrue(result.contains("Undefined"), "Unexpected search results ${result}");
    }
}
