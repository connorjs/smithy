/*
 * Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.smithy.model.loader;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.SourceException;
import software.amazon.smithy.model.node.Node;
import software.amazon.smithy.model.selector.Selector;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.shapes.StringShape;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.model.traits.DocumentationTrait;
import software.amazon.smithy.model.traits.DynamicTrait;
import software.amazon.smithy.model.traits.TraitDefinition;
import software.amazon.smithy.model.traits.TraitFactory;
import software.amazon.smithy.model.validation.ValidationEvent;
import software.amazon.smithy.utils.MapUtils;

public class LoaderVisitorTest {
    private static final TraitFactory FACTORY = TraitFactory.createServiceFactory();

    @Test
    public void callingOnEndTwiceIsIdempotent() {
        LoaderVisitor visitor = new LoaderVisitor(FACTORY);

        assertThat(visitor.onEnd(), is(visitor.onEnd()));
    }

    @Test
    public void cannotDuplicateTraitDefs() {
        Assertions.assertThrows(SourceException.class, () -> {
            LoaderVisitor visitor = new LoaderVisitor(FACTORY);
            StringShape def1 = StringShape.builder()
                    .id("foo.baz#Bar")
                    .addTrait(TraitDefinition.builder().build())
                    .build();
            StringShape def2 = StringShape.builder()
                    .id("foo.baz#Bar")
                    .addTrait(TraitDefinition.builder().selector(Selector.parse("string")).build())
                    .build();

            visitor.onShape(def1);
            visitor.onShape(def2);
            visitor.onEnd();
        });
    }

    @Test
    public void ignoresDuplicateTraitDefsFromPrelude() {
        LoaderVisitor visitor = new LoaderVisitor(FACTORY);
        Shape def1 = StructureShape.builder()
                .id("smithy.api#deprecated")
                .addTrait(TraitDefinition.builder().build())
                .build();
        Shape def2 = StructureShape.builder()
                .id("smithy.api#deprecated")
                .addTrait(TraitDefinition.builder().build())
                .build();

        visitor.onShape(def1);
        visitor.onShape(def2);
        List<ValidationEvent> events = visitor.onEnd().getValidationEvents();

        assertThat(events, empty());
    }

    @Test
    public void cannotDuplicateNonPreludeTraitDefs() {
        Assertions.assertThrows(SourceException.class, () -> {
            LoaderVisitor visitor = new LoaderVisitor(FACTORY);
            Shape def1 = StructureShape.builder()
                    .id("smithy.example#deprecated")
                    .addTrait(TraitDefinition.builder().build())
                    .build();
            Shape def2 = StructureShape.builder()
                    .id("smithy.example#deprecated")
                    .addTrait(TraitDefinition.builder().build())
                    .build();

            visitor.onShape(def1);
            visitor.onShape(def2);
            visitor.onEnd();
        });
    }

    @Test
    public void cannotDuplicateTraits() {
        LoaderVisitor visitor = new LoaderVisitor(FACTORY);
        ShapeId id = ShapeId.from("foo.bam#Boo");
        visitor.onShape(StringShape.builder().id(id));
        visitor.onTrait(id, DocumentationTrait.ID, Node.from("abc"));
        visitor.onTrait(id, DocumentationTrait.ID, Node.from("def"));
        List<ValidationEvent> events = visitor.onEnd().getValidationEvents();

        assertThat(events, not(empty()));
    }

    @Test
    public void createsDynamicTraitWhenTraitFactoryReturnsEmpty() {
        LoaderVisitor visitor = new LoaderVisitor(FACTORY);
        Shape def = StructureShape.builder()
                .id("foo.baz#Bar")
                .addTrait(TraitDefinition.builder().build())
                .build();
        visitor.onShape(def);
        ShapeId id = ShapeId.from("foo.bam#Boo");
        visitor.onShape(StringShape.builder().id(id));
        visitor.onTrait(id, ShapeId.from("foo.baz#Bar"), Node.from(true));
        Model model = visitor.onEnd().unwrap();

        assertThat(model.expectShape(id).findTrait("foo.baz#Bar").get(),
                   instanceOf(DynamicTrait.class));
    }

    @Test
    public void failsWhenTraitNotFound() {
        LoaderVisitor visitor = new LoaderVisitor(FACTORY);
        ShapeId id = ShapeId.from("foo.bam#Boo");
        visitor.onShape(StringShape.builder().id(id));
        visitor.onTrait(id, ShapeId.from("foo.baz#Bar"), Node.from(true));
        List<ValidationEvent> events = visitor.onEnd().getValidationEvents();

        assertThat(events, not(empty()));
    }

    @Test
    public void supportsCustomProperties() {
        Map<String, Object> properties = MapUtils.of("a", true, "b", new HashMap<>());
        LoaderVisitor visitor = new LoaderVisitor(TraitFactory.createServiceFactory(), properties);

        assertThat(visitor.getProperty("a").get(), equalTo(true));
        assertThat(visitor.getProperty("b").get(), equalTo(new HashMap<>()));
        assertThat(visitor.getProperty("a", Boolean.class).get(), equalTo(true));
        assertThat(visitor.getProperty("b", Map.class).get(), equalTo(new HashMap<>()));
    }

    @Test
    public void assertsCorrectPropertyType() {
        Assertions.assertThrows(ClassCastException.class, () -> {
            Map<String, Object> properties = MapUtils.of("a", true);
            LoaderVisitor visitor = new LoaderVisitor(TraitFactory.createServiceFactory(), properties);

            visitor.getProperty("a", Integer.class).get();
        });
    }

    /**
     * Members are only ever added to non-existent shapes when parsing a containing
     * shape fails. Normally, the members are added to the containing builder at
     * the end of the loading process. Members are added to the LoaderVisitor first,
     * followed by the containing shape. If there's a syntax error or a duplicate
     * shape error when loading the containing shape, then the members are present
     * in the LoaderVisitor but the containing shape is not. In this event, the
     * LoaderVisitor just logs and continues. The loading process will eventually
     * fail with the syntax error.
     */
    @Test
    public void ignoresAddingMemberToNonExistentShape() {
        LoaderVisitor visitor = new LoaderVisitor(FACTORY);
        visitor.onShape(MemberShape.builder().id("foo.baz#Bar$bam").target("foo.baz#Bam"));
        visitor.onEnd().unwrap();
    }

    @Test
    public void errorWhenShapesConflict() {
        Assertions.assertThrows(SourceException.class, () -> {
            LoaderVisitor visitor = new LoaderVisitor(FACTORY);
            Shape shape = StringShape.builder().id("smithy.foo#Baz").build();
            visitor.onShape(shape);
            visitor.onShape(shape);
            visitor.onEnd();
        });
    }

    @Test
    public void ignoresDuplicateFiles() {
        URL file = getClass().getResource("valid/trait-definitions.smithy");
        Model model = Model.assembler().addImport(file).assemble().unwrap();
        Model.assembler().addModel(model).addImport(file).assemble().unwrap();
    }
}
