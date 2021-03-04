package recipe.lang.agents;

import org.petitparser.parser.Parser;
import org.petitparser.parser.combinators.SettableParser;
import org.petitparser.parser.primitive.CharacterParser;
import org.petitparser.parser.primitive.StringParser;
import org.w3c.dom.CDATASection;
import recipe.lang.actions.ActionParser;
import recipe.lang.actions.SendAction;
import recipe.lang.conditions.Condition;
import recipe.lang.conditions.ConditionParser;

import java.util.List;

public class AgentParser {
    Parser parser;
    private ConditionParser conditionParser;
    private ActionParser actionParser;

    public Parser getParser(){
        return parser;
    }

    public boolean parse(String s){
        Parser start = parser.end();

        return start.accept(s);
    }

    public AgentParser(){
        conditionParser = new ConditionParser();
        actionParser = new ActionParser(conditionParser);
        parser = createParser(conditionParser, actionParser);
    }

    public AgentParser(ConditionParser conditionParser, ActionParser actionParser){
        this.conditionParser = conditionParser;
        this.actionParser = actionParser;
        parser = createParser(this.conditionParser, this.actionParser);
    }

    private Parser createParser(ConditionParser conditionParser, ActionParser actionParser){
        SettableParser parser = SettableParser.undefined();
        SettableParser basic = SettableParser.undefined();
        Parser condition = conditionParser.getParser();
        Parser action = actionParser.getParser();

        basic.set((CharacterParser.of('(').trim()).seq(action).seq((CharacterParser.of(')').trim()))
                .map((List<Object> values) -> {
                    return (SendAction) values.get(0);
                }));

        parser.set((basic.seq(StringParser.of("+").trim()).seq(parser))
                .map((List<Object> values) -> {
                    return new Choice((Agent) values.get(0), (Agent) values.get(2));
                })
                .or(basic.seq(StringParser.of(";").trim()).seq(parser)
                        .map((List<Object> values) -> {
                            return new Sequence((Agent) values.get(0), (Agent) values.get(2));
                        }))
                .or((StringParser.of("<").trim().seq(condition).seq(StringParser.of(">").trim())).seq(parser)
                        .map((List<Object> values) -> {
                            return new Guarded((Condition) values.get(1), (Agent) values.get(3));
                        }))
                .or(basic.seq(parser)
                        .map((List<Object> values) -> {
                            return (SendAction) values.get(0);
                        }))
                .or(action));
        return parser;
    }

}
