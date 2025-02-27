package recipe.analysis;

import org.junit.Test;
import org.petitparser.context.Result;
import org.petitparser.parser.Parser;
import recipe.lang.System;
import recipe.lang.utils.exceptions.RelabellingTypeException;

import java.nio.file.Files;
import java.nio.file.Paths;

public class ToNuXmvTest {

    @Test
    public void transform() throws Exception {
        String script = String.join("\n", Files.readAllLines(Paths.get("./bigger-example.txt")));

        Parser system = System.parser().end();
        Result r = system.parse(script);
        System s = r.get();
        try {
            String transform = ToNuXmv.transform(s);
            java.lang.System.out.println(transform);
        } catch (RelabellingTypeException e) {
            e.printStackTrace();
            assert r.isFailure();
        }
        assert r.isSuccess();
    }

    @Test
    public void nuxmvLTL() throws Exception {
        String script = String.join("\n", Files.readAllLines(Paths.get("./bigger-example.txt")));
        Parser system = System.parser().end();
        Result r = system.parse(script);
        System s = r.get();
        try {
            ToNuXmv.nuxmvModelChecking(s);
//            java.lang.System.out.println(transform);
        } catch (Exception e) {
            e.printStackTrace();
            assert r.isFailure();
        }
        assert r.isSuccess();

    }

    @Test
    public void transform2() throws Exception {
        String script = String.join("\n", Files.readAllLines(Paths.get("./bigger-example.txt")));
        Parser system = System.parser().end();
        Result r = system.parse(script);
        System s = r.get();
        try {
            String out = ToNuXmv.transform(s);
            java.lang.System.out.println(out);
        } catch (Exception e) {
            e.printStackTrace();
            assert r.isFailure();
        }
        assert r.isSuccess();

    }
}