/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.painless.antlr;

import org.antlr.v4.runtime.ANTLRInputStream;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.DiagnosticErrorListener;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.atn.PredictionMode;
import org.elasticsearch.painless.CompilerSettings;
import org.elasticsearch.painless.Operation;
import org.elasticsearch.painless.Variables.Reserved;
import org.elasticsearch.painless.antlr.PainlessParser.AfterthoughtContext;
import org.elasticsearch.painless.antlr.PainlessParser.ArgumentsContext;
import org.elasticsearch.painless.antlr.PainlessParser.AssignmentContext;
import org.elasticsearch.painless.antlr.PainlessParser.BinaryContext;
import org.elasticsearch.painless.antlr.PainlessParser.BlockContext;
import org.elasticsearch.painless.antlr.PainlessParser.BoolContext;
import org.elasticsearch.painless.antlr.PainlessParser.BraceaccessContext;
import org.elasticsearch.painless.antlr.PainlessParser.BreakContext;
import org.elasticsearch.painless.antlr.PainlessParser.CallinvokeContext;
import org.elasticsearch.painless.antlr.PainlessParser.CastContext;
import org.elasticsearch.painless.antlr.PainlessParser.ChainprecContext;
import org.elasticsearch.painless.antlr.PainlessParser.CompContext;
import org.elasticsearch.painless.antlr.PainlessParser.ConditionalContext;
import org.elasticsearch.painless.antlr.PainlessParser.ContinueContext;
import org.elasticsearch.painless.antlr.PainlessParser.DeclContext;
import org.elasticsearch.painless.antlr.PainlessParser.DeclarationContext;
import org.elasticsearch.painless.antlr.PainlessParser.DecltypeContext;
import org.elasticsearch.painless.antlr.PainlessParser.DeclvarContext;
import org.elasticsearch.painless.antlr.PainlessParser.DelimiterContext;
import org.elasticsearch.painless.antlr.PainlessParser.DoContext;
import org.elasticsearch.painless.antlr.PainlessParser.DynamicContext;
import org.elasticsearch.painless.antlr.PainlessParser.EmptyContext;
import org.elasticsearch.painless.antlr.PainlessParser.ExprContext;
import org.elasticsearch.painless.antlr.PainlessParser.ExpressionContext;
import org.elasticsearch.painless.antlr.PainlessParser.ExprprecContext;
import org.elasticsearch.painless.antlr.PainlessParser.FalseContext;
import org.elasticsearch.painless.antlr.PainlessParser.FieldaccessContext;
import org.elasticsearch.painless.antlr.PainlessParser.ForContext;
import org.elasticsearch.painless.antlr.PainlessParser.IfContext;
import org.elasticsearch.painless.antlr.PainlessParser.InitializerContext;
import org.elasticsearch.painless.antlr.PainlessParser.NewarrayContext;
import org.elasticsearch.painless.antlr.PainlessParser.NewobjectContext;
import org.elasticsearch.painless.antlr.PainlessParser.NullContext;
import org.elasticsearch.painless.antlr.PainlessParser.NumericContext;
import org.elasticsearch.painless.antlr.PainlessParser.OperatorContext;
import org.elasticsearch.painless.antlr.PainlessParser.PostContext;
import org.elasticsearch.painless.antlr.PainlessParser.PreContext;
import org.elasticsearch.painless.antlr.PainlessParser.ReadContext;
import org.elasticsearch.painless.antlr.PainlessParser.ReturnContext;
import org.elasticsearch.painless.antlr.PainlessParser.SecondaryContext;
import org.elasticsearch.painless.antlr.PainlessParser.SingleContext;
import org.elasticsearch.painless.antlr.PainlessParser.SourceContext;
import org.elasticsearch.painless.antlr.PainlessParser.StatementContext;
import org.elasticsearch.painless.antlr.PainlessParser.StaticContext;
import org.elasticsearch.painless.antlr.PainlessParser.StringContext;
import org.elasticsearch.painless.antlr.PainlessParser.ThrowContext;
import org.elasticsearch.painless.antlr.PainlessParser.TrailerContext;
import org.elasticsearch.painless.antlr.PainlessParser.TrapContext;
import org.elasticsearch.painless.antlr.PainlessParser.TrueContext;
import org.elasticsearch.painless.antlr.PainlessParser.TryContext;
import org.elasticsearch.painless.antlr.PainlessParser.UnaryContext;
import org.elasticsearch.painless.antlr.PainlessParser.VariableContext;
import org.elasticsearch.painless.antlr.PainlessParser.WhileContext;
import org.elasticsearch.painless.node.AExpression;
import org.elasticsearch.painless.node.ALink;
import org.elasticsearch.painless.node.ANode;
import org.elasticsearch.painless.node.AStatement;
import org.elasticsearch.painless.node.EBinary;
import org.elasticsearch.painless.node.EBool;
import org.elasticsearch.painless.node.EBoolean;
import org.elasticsearch.painless.node.EChain;
import org.elasticsearch.painless.node.EComp;
import org.elasticsearch.painless.node.EConditional;
import org.elasticsearch.painless.node.EDecimal;
import org.elasticsearch.painless.node.EExplicit;
import org.elasticsearch.painless.node.ENull;
import org.elasticsearch.painless.node.ENumeric;
import org.elasticsearch.painless.node.EUnary;
import org.elasticsearch.painless.node.LBrace;
import org.elasticsearch.painless.node.LCall;
import org.elasticsearch.painless.node.LCast;
import org.elasticsearch.painless.node.LField;
import org.elasticsearch.painless.node.LNewArray;
import org.elasticsearch.painless.node.LNewObj;
import org.elasticsearch.painless.node.LStatic;
import org.elasticsearch.painless.node.LString;
import org.elasticsearch.painless.node.LVariable;
import org.elasticsearch.painless.node.SBlock;
import org.elasticsearch.painless.node.SBreak;
import org.elasticsearch.painless.node.SCatch;
import org.elasticsearch.painless.node.SContinue;
import org.elasticsearch.painless.node.SDeclBlock;
import org.elasticsearch.painless.node.SDeclaration;
import org.elasticsearch.painless.node.SDo;
import org.elasticsearch.painless.node.SExpression;
import org.elasticsearch.painless.node.SFor;
import org.elasticsearch.painless.node.SIf;
import org.elasticsearch.painless.node.SIfElse;
import org.elasticsearch.painless.node.SReturn;
import org.elasticsearch.painless.node.SSource;
import org.elasticsearch.painless.node.SThrow;
import org.elasticsearch.painless.node.STry;
import org.elasticsearch.painless.node.SWhile;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts the ANTLR tree to a Painless tree.
 */
public final class Walker extends PainlessParserBaseVisitor<Object> {

    public static SSource buildPainlessTree(String source, Reserved reserved, CompilerSettings settings) {
        return new Walker(source, reserved, settings).source;
    }

    private final Reserved reserved;
    private final SSource source;
    private final CompilerSettings settings;

    private Walker(String source, Reserved reserved, CompilerSettings settings) {
        this.reserved = reserved;
        this.settings = settings;
        this.source = (SSource)visit(buildAntlrTree(source));
    }

    private SourceContext buildAntlrTree(String source) {
        ANTLRInputStream stream = new ANTLRInputStream(source);
        PainlessLexer lexer = new ErrorHandlingLexer(stream);
        PainlessParser parser = new PainlessParser(new CommonTokenStream(lexer));
        ParserErrorStrategy strategy = new ParserErrorStrategy();

        lexer.removeErrorListeners();
        parser.removeErrorListeners();

        if (settings.isPicky()) {
            setupPicky(parser);
        }

        parser.setErrorHandler(strategy);

        return parser.source();
    }

    private void setupPicky(PainlessParser parser) {
        // Diagnostic listener invokes syntaxError on other listeners for ambiguity issues,
        parser.addErrorListener(new DiagnosticErrorListener(true));
        // a second listener to fail the test when the above happens.
        parser.addErrorListener(new BaseErrorListener() {
            @Override
            public void syntaxError(final Recognizer<?,?> recognizer, final Object offendingSymbol, final int line,
                                    final int charPositionInLine, final String msg, final RecognitionException e) {
                throw new AssertionError("line: " + line + ", offset: " + charPositionInLine +
                    ", symbol:" + offendingSymbol + " " + msg);
            }
        });

        // Enable exact ambiguity detection (costly). we enable exact since its the default for
        // DiagnosticErrorListener, life is too short to think about what 'inexact ambiguity' might mean.
        parser.getInterpreter().setPredictionMode(PredictionMode.LL_EXACT_AMBIG_DETECTION);
    }

    private int line(ParserRuleContext ctx) {
        return ctx.getStart().getLine();
    }

    private int offset(ParserRuleContext ctx) {
        return ctx.getStart().getStartIndex();
    }

    private String location(ParserRuleContext ctx) {
        return "[ " + ctx.getStart().getLine() + " : " + ctx.getStart().getCharPositionInLine() + " ]";
    }

    @Override
    public Object visitSource(SourceContext ctx) {
        List<AStatement> statements = new ArrayList<>();

        for (StatementContext statement : ctx.statement()) {
            statements.add((AStatement)visit(statement));
        }

        return new SSource(line(ctx), offset(ctx), location(ctx), statements);
    }

    @Override
    public Object visitIf(IfContext ctx) {
        AExpression expression = (AExpression)visitExpression(ctx.expression());
        SBlock ifblock = (SBlock)visit(ctx.trailer(0));

        if (ctx.trailer().size() > 1) {
            SBlock elseblock = (SBlock)visit(ctx.trailer(1));

            return new SIfElse(line(ctx), offset(ctx), location(ctx), expression, ifblock, elseblock);
        } else {
            return new SIf(line(ctx), offset(ctx), location(ctx), expression, ifblock);
        }
    }

    @Override
    public Object visitWhile(WhileContext ctx) {
        if (settings.getMaxLoopCounter() > 0) {
            reserved.usesLoop();
        }

        AExpression expression = (AExpression)visitExpression(ctx.expression());

        if (ctx.trailer() != null) {
            SBlock block = (SBlock)visit(ctx.trailer());

            return new SWhile(line(ctx), offset(ctx), location(ctx), settings.getMaxLoopCounter(), expression, block);
        } else if (ctx.empty() != null) {
            return new SWhile(line(ctx), offset(ctx), location(ctx), settings.getMaxLoopCounter(), expression, null);
        } else {
            throw new IllegalStateException("Error " + location(ctx) + " Illegal tree structure.");
        }
    }

    @Override
    public Object visitDo(DoContext ctx) {
        if (settings.getMaxLoopCounter() > 0) {
            reserved.usesLoop();
        }

        AExpression expression = (AExpression)visitExpression(ctx.expression());
        SBlock block = (SBlock)visit(ctx.block());

        return new SDo(line(ctx), offset(ctx), location(ctx), settings.getMaxLoopCounter(), block, expression);
    }

    @Override
    public Object visitFor(ForContext ctx) {
        if (settings.getMaxLoopCounter() > 0) {
            reserved.usesLoop();
        }

        ANode initializer = ctx.initializer() == null ? null : (ANode)visit(ctx.initializer());
        AExpression expression = ctx.expression() == null ? null : (AExpression)visitExpression(ctx.expression());
        AExpression afterthought = ctx.afterthought() == null ? null : (AExpression)visit(ctx.afterthought());

        if (ctx.trailer() != null) {
            SBlock block = (SBlock)visit(ctx.trailer());

            return new SFor(line(ctx), offset(ctx), location(ctx),
                settings.getMaxLoopCounter(), initializer, expression, afterthought, block);
        } else if (ctx.empty() != null) {
            return new SFor(line(ctx), offset(ctx), location(ctx),
                settings.getMaxLoopCounter(), initializer, expression, afterthought, null);
        } else {
            throw new IllegalStateException("Error " + location(ctx) + " Illegal tree structure.");
        }
    }

    @Override
    public Object visitDecl(DeclContext ctx) {
        return visit(ctx.declaration());
    }

    @Override
    public Object visitContinue(ContinueContext ctx) {
        return new SContinue(line(ctx), offset(ctx), location(ctx));
    }

    @Override
    public Object visitBreak(BreakContext ctx) {
        return new SBreak(line(ctx), offset(ctx), location(ctx));
    }

    @Override
    public Object visitReturn(ReturnContext ctx) {
        AExpression expression = (AExpression)visitExpression(ctx.expression());

        return new SReturn(line(ctx), offset(ctx), location(ctx), expression);
    }

    @Override
    public Object visitTry(TryContext ctx) {
        SBlock block = (SBlock)visit(ctx.block());
        List<SCatch> catches = new ArrayList<>();

        for (TrapContext trap : ctx.trap()) {
            catches.add((SCatch)visit(trap));
        }

        return new STry(line(ctx), offset(ctx), location(ctx), block, catches);
    }

    @Override
    public Object visitThrow(ThrowContext ctx) {
        AExpression expression = (AExpression)visitExpression(ctx.expression());

        return new SThrow(line(ctx), offset(ctx), location(ctx), expression);
    }

    @Override
    public Object visitExpr(ExprContext ctx) {
        AExpression expression = (AExpression)visitExpression(ctx.expression());

        return new SExpression(line(ctx), offset(ctx), location(ctx), expression);
    }

    @Override
    public Object visitTrailer(TrailerContext ctx) {
        if (ctx.block() != null) {
            return visit(ctx.block());
        } else if (ctx.statement() != null) {
            List<AStatement> statements = new ArrayList<>();
            statements.add((AStatement)visit(ctx.statement()));

            return new SBlock(line(ctx), offset(ctx), location(ctx), statements);
        } else {
            throw new IllegalStateException("Error " + location(ctx) + " Illegal tree structure.");
        }
    }

    @Override
    public Object visitBlock(BlockContext ctx) {
        if (ctx.statement().isEmpty()) {
            return null;
        } else {
            List<AStatement> statements = new ArrayList<>();

            for (StatementContext statement : ctx.statement()) {
                statements.add((AStatement)visit(statement));
            }

            return new SBlock(line(ctx), offset(ctx), location(ctx), statements);
        }
    }

    @Override
    public Object visitEmpty(EmptyContext ctx) {
        throw new IllegalStateException("Error " + location(ctx) + " Illegal tree structure.");
    }

    @Override
    public Object visitInitializer(InitializerContext ctx) {
        if (ctx.declaration() != null) {
            return visit(ctx.declaration());
        } else if (ctx.expression() != null) {
            return visitExpression(ctx.expression());
        } else {
            throw new IllegalStateException("Error " + location(ctx) + " Illegal tree structure.");
        }
    }

    @Override
    public Object visitAfterthought(AfterthoughtContext ctx) {
        return visitExpression(ctx.expression());
    }

    @Override
    public Object visitDeclaration(DeclarationContext ctx) {
        String type = ctx.decltype().getText();
        List<SDeclaration> declarations = new ArrayList<>();

        for (DeclvarContext declvar : ctx.declvar()) {
            String name = declvar.ID().getText();
            AExpression expression = declvar.expression() == null ? null : (AExpression)visitExpression(declvar.expression());

            declarations.add(new SDeclaration(line(declvar), offset(declvar), location(declvar), type, name, expression));
        }

        return new SDeclBlock(line(ctx), offset(ctx), location(ctx), declarations);
    }

    @Override
    public Object visitDecltype(DecltypeContext ctx) {
        throw new IllegalStateException("Error " + location(ctx) + " Illegal tree structure.");
    }

    @Override
    public Object visitDeclvar(DeclvarContext ctx) {
        throw new IllegalStateException("Error " + location(ctx) + " Illegal tree structure.");
    }

    @Override
    public Object visitTrap(TrapContext ctx) {
        String type = ctx.TYPE().getText();
        String name = ctx.ID().getText();
        SBlock block = (SBlock)visit(ctx.block());

        return new SCatch(line(ctx), offset(ctx), location(ctx), type, name, block);
    }

    @Override
    public Object visitDelimiter(DelimiterContext ctx) {
        throw new IllegalStateException("Error " + location(ctx) + " Illegal tree structure.");
    }

    private Object visitExpression(ExpressionContext ctx) {
        Object expression = visit(ctx);

        if (expression instanceof List) {
            @SuppressWarnings("unchecked")
            List<ALink> links = (List<ALink>)expression;

            return new EChain(line(ctx), offset(ctx), location(ctx), links, false, false, null, null);
        } else {
            return expression;
        }
    }

    @Override
    public Object visitSingle(SingleContext ctx) {
        return visit(ctx.unary());
    }

    @Override
    public Object visitBinary(BinaryContext ctx) {
        AExpression left = (AExpression)visitExpression(ctx.expression(0));
        AExpression right = (AExpression)visitExpression(ctx.expression(1));
        final Operation operation;

        if (ctx.MUL() != null) {
            operation = Operation.MUL;
        } else if (ctx.DIV() != null) {
            operation = Operation.DIV;
        } else if (ctx.REM() != null) {
            operation = Operation.REM;
        } else if (ctx.ADD() != null) {
            operation = Operation.ADD;
        } else if (ctx.SUB() != null) {
            operation = Operation.SUB;
        } else if (ctx.LSH() != null) {
            operation = Operation.LSH;
        } else if (ctx.RSH() != null) {
            operation = Operation.RSH;
        } else if (ctx.USH() != null) {
            operation = Operation.USH;
        } else if (ctx.BWAND() != null) {
            operation = Operation.BWAND;
        } else if (ctx.XOR() != null) {
            operation = Operation.XOR;
        } else if (ctx.BWOR() != null) {
            operation = Operation.BWOR;
        } else {
            throw new IllegalStateException("Error " + location(ctx) + ": Unexpected state.");
        }

        return new EBinary(line(ctx), offset(ctx), location(ctx), operation, left, right);
    }

    @Override
    public Object visitComp(CompContext ctx) {
        AExpression left = (AExpression)visitExpression(ctx.expression(0));
        AExpression right = (AExpression)visitExpression(ctx.expression(1));
        final Operation operation;

        if (ctx.LT() != null) {
            operation = Operation.LT;
        } else if (ctx.LTE() != null) {
            operation = Operation.LTE;
        } else if (ctx.GT() != null) {
            operation = Operation.GT;
        } else if (ctx.GTE() != null) {
            operation = Operation.GTE;
        } else if (ctx.EQ() != null) {
            operation = Operation.EQ;
        } else if (ctx.EQR() != null) {
            operation = Operation.EQR;
        } else if (ctx.NE() != null) {
            operation = Operation.NE;
        } else if (ctx.NER() != null) {
            operation = Operation.NER;
        } else {
            throw new IllegalStateException("Error " + location(ctx) + ": Unexpected state.");
        }

        return new EComp(line(ctx), offset(ctx), location(ctx), operation, left, right);
    }

    @Override
    public Object visitBool(BoolContext ctx) {
        AExpression left = (AExpression)visitExpression(ctx.expression(0));
        AExpression right = (AExpression)visitExpression(ctx.expression(1));
        final Operation operation;

        if (ctx.BOOLAND() != null) {
            operation = Operation.AND;
        } else if (ctx.BOOLOR() != null) {
            operation = Operation.OR;
        } else {
            throw new IllegalStateException("Error " + location(ctx) + ": Unexpected state.");
        }

        return new EBool(line(ctx), offset(ctx), location(ctx), operation, left, right);
    }

    @Override
    public Object visitConditional(ConditionalContext ctx) {
        AExpression condition = (AExpression)visitExpression(ctx.expression(0));
        AExpression left = (AExpression)visitExpression(ctx.expression(1));
        AExpression right = (AExpression)visitExpression(ctx.expression(2));

        return new EConditional(line(ctx), offset(ctx), location(ctx), condition, left, right);
    }

    @Override
    public Object visitAssignment(AssignmentContext ctx) {
        @SuppressWarnings("unchecked")
        List<ALink> links = (List<ALink>)visit(ctx.chain());
        final Operation operation;

        if (ctx.ASSIGN() != null) {
            operation = null;
        } else if (ctx.AMUL() != null) {
            operation = Operation.MUL;
        } else if (ctx.ADIV() != null) {
            operation = Operation.DIV;
        } else if (ctx.AREM() != null) {
            operation = Operation.REM;
        } else if (ctx.AADD() != null) {
            operation = Operation.ADD;
        } else if (ctx.ASUB() != null) {
            operation = Operation.SUB;
        } else if (ctx.ALSH() != null) {
            operation = Operation.LSH;
        } else if (ctx.ARSH() != null) {
            operation = Operation.RSH;
        } else if (ctx.AUSH() != null) {
            operation = Operation.USH;
        } else if (ctx.AAND() != null) {
            operation = Operation.BWAND;
        } else if (ctx.AXOR() != null) {
            operation = Operation.XOR;
        } else if (ctx.AOR() != null) {
            operation = Operation.BWOR;
        } else {
            throw new IllegalStateException("Error " + location(ctx) + ": Illegal tree structure.");
        }

        AExpression expression = (AExpression)visitExpression(ctx.expression());

        return new EChain(line(ctx), offset(ctx), location(ctx), links, false, false, operation, expression);
    }

    private Object visitUnary(UnaryContext ctx) {
        Object expression = visit(ctx);

        if (expression instanceof List) {
            @SuppressWarnings("unchecked")
            List<ALink> links = (List<ALink>)expression;

            return new EChain(line(ctx), offset(ctx), location(ctx), links, false, false, null, null);
        } else {
            return expression;
        }
    }

    @Override
    public Object visitPre(PreContext ctx) {
        @SuppressWarnings("unchecked")
        List<ALink> links = (List<ALink>)visit(ctx.chain());
        final Operation operation;

        if (ctx.INCR() != null) {
            operation = Operation.INCR;
        } else if (ctx.DECR() != null) {
            operation = Operation.DECR;
        } else {
            throw new IllegalStateException("Error " + location(ctx) + ": Illegal tree structure.");
        }

        return new EChain(line(ctx), offset(ctx), location(ctx), links, true, false, operation, null);
    }

    @Override
    public Object visitPost(PostContext ctx) {
        @SuppressWarnings("unchecked")
        List<ALink> links = (List<ALink>)visit(ctx.chain());
        final Operation operation;

        if (ctx.INCR() != null) {
            operation = Operation.INCR;
        } else if (ctx.DECR() != null) {
            operation = Operation.DECR;
        } else {
            throw new IllegalStateException("Error " + location(ctx) + ": Illegal tree structure.");
        }

        return new EChain(line(ctx), offset(ctx), location(ctx), links, false, true, operation, null);
    }

    @Override
    public Object visitRead(ReadContext ctx) {
        return visit(ctx.chain());
    }

    @Override
    public Object visitNumeric(NumericContext ctx) {
        final boolean negate = ctx.parent instanceof OperatorContext && ((OperatorContext)ctx.parent).SUB() != null;

        if (ctx.DECIMAL() != null) {
            return new EDecimal(line(ctx), offset(ctx), location(ctx), (negate ? "-" : "") + ctx.DECIMAL().getText());
        } else if (ctx.HEX() != null) {
            return new ENumeric(line(ctx), offset(ctx), location(ctx), (negate ? "-" : "") + ctx.HEX().getText().substring(2), 16);
        } else if (ctx.INTEGER() != null) {
            return new ENumeric(line(ctx), offset(ctx), location(ctx), (negate ? "-" : "") + ctx.INTEGER().getText(), 10);
        } else if (ctx.OCTAL() != null) {
            return new ENumeric(line(ctx), offset(ctx), location(ctx), (negate ? "-" : "") + ctx.OCTAL().getText().substring(1), 8);
        } else {
            throw new IllegalStateException("Error " + location(ctx) + ": Illegal tree structure.");
        }
    }

    @Override
    public Object visitTrue(TrueContext ctx) {
        return new EBoolean(line(ctx), offset(ctx), location(ctx), true);
    }

    @Override
    public Object visitFalse(FalseContext ctx) {
        return new EBoolean(line(ctx), offset(ctx), location(ctx), false);
    }

    @Override
    public Object visitNull(NullContext ctx) {
        return new ENull(line(ctx), offset(ctx), location(ctx));
    }

    @Override
    public Object visitOperator(OperatorContext ctx) {
        if (ctx.SUB() != null && ctx.unary() instanceof NumericContext) {
            return visit(ctx.unary());
        } else {
            AExpression expression = (AExpression)visitUnary(ctx.unary());
            final Operation operation;

            if (ctx.BOOLNOT() != null) {
                operation = Operation.NOT;
            } else if (ctx.BWNOT() != null) {
                operation = Operation.BWNOT;
            } else if (ctx.ADD() != null) {
                operation = Operation.ADD;
            } else if (ctx.SUB() != null) {
                operation = Operation.SUB;
            } else {
                throw new IllegalStateException("Error " + location(ctx) + " Illegal tree structure.");
            }

            return new EUnary(line(ctx), offset(ctx), location(ctx), operation, expression);
        }
    }

    @Override
    public Object visitCast(CastContext ctx) {
        String type = ctx.decltype().getText();
        Object child = visit(ctx.unary());

        if (child instanceof List) {
            @SuppressWarnings("unchecked")
            List<ALink> links = (List<ALink>)child;
            links.add(new LCast(line(ctx), offset(ctx), location(ctx), type));

            return links;
        } else {
            return new EExplicit(line(ctx), offset(ctx), location(ctx), type, (AExpression)child);
        }
    }

    @Override
    public Object visitDynamic(DynamicContext ctx) {
        Object child = visit(ctx.primary());

        if (child instanceof List) {
            @SuppressWarnings("unchecked")
            List<ALink> links = (List<ALink>)child;

            for (SecondaryContext secondary : ctx.secondary()) {
                links.add((ALink)visit(secondary));
            }

            return links;
        } else if (!ctx.secondary().isEmpty()) {
            throw new IllegalStateException("Error " + location(ctx) + " Illegal tree structure.");
        } else {
            return child;
        }
    }

    @Override
    public Object visitStatic(StaticContext ctx) {
        String type = ctx.decltype().getText();
        List<ALink> links = new ArrayList<>();

        links.add(new LStatic(line(ctx), offset(ctx), location(ctx), type));
        links.add((ALink)visit(ctx.dot()));

        for (SecondaryContext secondary : ctx.secondary()) {
            links.add((ALink)visit(secondary));
        }

        return links;
    }

    @Override
    public Object visitNewarray(NewarrayContext ctx) {
        String type = ctx.TYPE().getText();
        List<AExpression> expressions = new ArrayList<>();

        for (ExpressionContext expression : ctx.expression()) {
            expressions.add((AExpression)visitExpression(expression));
        }

        List<ALink> links = new ArrayList<>();
        links.add(new LNewArray(line(ctx), offset(ctx), location(ctx), type, expressions));

        if (ctx.dot() != null) {
            links.add((ALink)visit(ctx.dot()));

            for (SecondaryContext secondary : ctx.secondary()) {
                links.add((ALink)visit(secondary));
            }
        } else if (!ctx.secondary().isEmpty()) {
            throw new IllegalStateException("Error " + location(ctx) + " Illegal tree structure.");
        }

        return links;
    }

    @Override
    public Object visitExprprec(ExprprecContext ctx) {
        return visit(ctx.expression());
    }

    @Override
    public Object visitChainprec(ChainprecContext ctx) {
        return visit(ctx.unary());
    }

    @Override
    public Object visitString(StringContext ctx) {
        String string = ctx.STRING().getText().substring(1, ctx.STRING().getText().length() - 1);
        List<ALink> links = new ArrayList<>();
        links.add(new LString(line(ctx), offset(ctx), location(ctx), string));

        return links;
    }

    @Override
    public Object visitVariable(VariableContext ctx) {
        String name = ctx.ID().getText();
        List<ALink> links = new ArrayList<>();
        links.add(new LVariable(line(ctx), offset(ctx), location(ctx), name));

        reserved.markReserved(name);

        return links;
    }

    @Override
    public Object visitNewobject(NewobjectContext ctx) {
        String type = ctx.TYPE().getText();
        List<AExpression> arguments = new ArrayList<>();

        for (ExpressionContext expression : ctx.arguments().expression()) {
            arguments.add((AExpression)visitExpression(expression));
        }

        List<ALink> links = new ArrayList<>();
        links.add(new LNewObj(line(ctx), offset(ctx), location(ctx), type, arguments));

        return links;
    }

    @Override
    public Object visitSecondary(SecondaryContext ctx) {
        if (ctx.dot() != null) {
            return visit(ctx.dot());
        } else if (ctx.brace() != null) {
            return visit(ctx.brace());
        } else {
            throw new IllegalStateException("Error " + location(ctx) + " Illegal tree structure.");
        }
    }

    @Override
    public Object visitCallinvoke(CallinvokeContext ctx) {
        String name = ctx.DOTID().getText();
        List<AExpression> arguments = new ArrayList<>();

        for (ExpressionContext expression : ctx.arguments().expression()) {
            arguments.add((AExpression)visitExpression(expression));
        }

        return new LCall(line(ctx), offset(ctx), location(ctx), name, arguments);
    }

    @Override
    public Object visitFieldaccess(FieldaccessContext ctx) {
        final String value;

        if (ctx.DOTID() != null) {
            value = ctx.DOTID().getText();
        } else if (ctx.DOTINTEGER() != null) {
            value = ctx.DOTINTEGER().getText();
        } else {
            throw new IllegalStateException("Error " + location(ctx) + " Illegal tree structure.");
        }

        return new LField(line(ctx), offset(ctx), location(ctx), value);
    }

    @Override
    public Object visitBraceaccess(BraceaccessContext ctx) {
        AExpression expression = (AExpression)visitExpression(ctx.expression());

        return new LBrace(line(ctx), offset(ctx), location(ctx), expression);
    }

    @Override
    public Object visitArguments(ArgumentsContext ctx) {
        throw new IllegalStateException("Error " + location(ctx) + " Illegal tree structure.");
    }
}
