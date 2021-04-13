package recipe.lang.expressions.arithmetic;

import org.petitparser.parser.primitive.CharacterParser;
import recipe.lang.exception.AttributeNotInStoreException;
import recipe.lang.exception.AttributeTypeException;
import recipe.lang.exception.RelabellingTypeException;
import recipe.lang.expressions.Expression;
import recipe.lang.expressions.TypedValue;
import recipe.lang.expressions.TypedVariable;
import recipe.lang.expressions.predicate.BooleanVariable;
import recipe.lang.expressions.strings.StringVariable;
import recipe.lang.store.Store;
import recipe.lang.utils.Parsing;
import recipe.lang.utils.TypingContext;

import java.util.List;
import java.util.Set;
import java.util.function.Function;

import static org.petitparser.parser.primitive.CharacterParser.digit;
import static org.petitparser.parser.primitive.CharacterParser.word;

public class NumberVariable extends ArithmeticExpression implements TypedVariable {
    String name;

    public NumberVariable(String name) {
        this.name = name;
    }

    @Override
    public NumberValue valueIn(Store store) throws AttributeNotInStoreException, AttributeTypeException {
        Object o = store.getValue(name);
        if (o == null) {
            throw new AttributeNotInStoreException();
        } else if(!NumberValue.class.equals(o.getClass())){
            throw new AttributeTypeException();
        }

        return (NumberValue) o;
    }

    @Override
    public ArithmeticExpression close(Store store, Set<String> CV) throws AttributeNotInStoreException, AttributeTypeException {
        if (!CV.contains(name)) {
            return this.valueIn(store);
        } else {
            return this;
        }
    }

    @Override
    public ArithmeticExpression relabel(Function<TypedVariable, Expression> relabelling) throws RelabellingTypeException {
        Expression result = relabelling.apply(this);
        if(!ArithmeticExpression.class.isAssignableFrom(result.getClass())){
            throw new RelabellingTypeException();
        } else{
            return (ArithmeticExpression) relabelling.apply( this);
        }
    }

    @Override
    public String toString(){
        return name;
    }

    @Override
    public boolean equals(Object o){
        return o.getClass().equals(NumberVariable.class) && name.equals(o);
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Boolean isValidValue(TypedValue val) {
        return val.getClass().equals(NumberValue.class);
    }

    public static org.petitparser.parser.Parser parser(TypingContext context){
        return Parsing.disjunctiveWordParser(context.get(NumberVariable.class), (String name) -> new NumberVariable(name));
    }

    public TypedVariable sameTypeWithName(String name){
        return new NumberVariable(name);
    }
}
