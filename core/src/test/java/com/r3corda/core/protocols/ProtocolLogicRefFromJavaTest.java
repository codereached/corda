package com.r3corda.core.protocols;


import org.jetbrains.annotations.*;
import org.junit.*;

import java.util.*;

public class ProtocolLogicRefFromJavaTest {

    private static class ParamType1 {
        final int value;

        ParamType1(int v) {
            value = v;
        }
    }

    private static class ParamType2 {
        final String value;

        ParamType2(String v) {
            value = v;
        }
    }

    private static class JavaProtocolLogic extends ProtocolLogic<Void> {

        public JavaProtocolLogic(ParamType1 A, ParamType2 b) {
        }

        @Override
        public Void call() {
            return null;
        }

        @NotNull
        @Override
        protected String getTopic() {
            throw new UnsupportedOperationException();
        }
    }

    private static class JavaNoArgProtocolLogic extends ProtocolLogic<Void> {

        public JavaNoArgProtocolLogic() {
        }

        @Override
        public Void call() {
            return null;
        }

        @NotNull
        @Override
        protected String getTopic() {
            throw new UnsupportedOperationException();
        }
    }

    @Test
    public void test() {
        Map<String, Set<String>> whiteList = new HashMap<>();
        Set<String> argsList = new HashSet<>();
        argsList.add(ParamType1.class.getName());
        argsList.add(ParamType2.class.getName());
        whiteList.put(JavaProtocolLogic.class.getName(), argsList);
        ProtocolLogicRefFactory factory = new ProtocolLogicRefFactory(whiteList);
        factory.create(JavaProtocolLogic.class, new ParamType1(1), new ParamType2("Hello Jack"));
    }

    @Test
    public void testNoArg() {
        Map<String, Set<String>> whiteList = new HashMap<>();
        whiteList.put(JavaNoArgProtocolLogic.class.getName(), new HashSet<>());
        ProtocolLogicRefFactory factory = new ProtocolLogicRefFactory(whiteList);
        factory.create(JavaNoArgProtocolLogic.class);
    }
}
