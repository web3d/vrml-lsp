package org.vrml.spec;

import java.util.List;

/**
 * The questions the three sources cannot answer, kept in code so they travel with the report.
 *
 * <p>The generator's rule - Xj3D decides names, UOM decides meaning - settles every conflict the
 * sources have with each other. What it cannot settle is whether those names are
 * <em>VRML97's</em>: Xj3D's node classes serve its X3D front end as well, and UOM is the X3D model.
 * Answering that needs the ISO VRML97 text or a person who has read it, so each question below
 * states what was checked locally and what is still open.
 *
 * <p>Edit this list when the table changes in a way a reviewer should have to think about. Lines
 * here are not facts about the output; they are the reasons the output might be wrong.
 */
final class SpecGenQuestions {

    private SpecGenQuestions() {
    }

    static List<String> all() {
        return List.of(
                "The table accepts more than VRML97 defines. Xj3D's classes carry fields its own"
                        + " VRML97 scope does not: Appearance.fillProperties/lineProperties"
                        + "/pointProperties/acousticProperties/shaders/textureProperties/alphaMode"
                        + "/alphaCutoff/backMaterial, Material.normalScale/occlusionStrength,"
                        + " Shape.castShadow/visible/bboxCenter/bboxSize/bboxDisplay,"
                        + " IndexedFaceSet.attrib/fogCoord, Light.global,"
                        + " Viewpoint.nearDistance/farDistance/centerOfRotation/retainUserOffsets,"
                        + " and bboxDisplay/autoRefresh/load/visible on grouping nodes. A VRML97-only"
                        + " implementation shipped in this workspace agrees - NIST's converter,"
                        + " X3D-Edit/X3dSourceFilePalette/release/modules/ext/Vrml97ToX3dNist.jar"
                        + " (iicm/vrml/pw) - never mentions most of them. Drop them from completion,"
                        + " or keep accepting them silently so files written for Xj3D still work?",
                "The table accepts less than VRML97 defines, for names X3D renamed."
                        + " NavigationInfo's MFString field is 'kind' in VRML97 and 'type' in X3D;"
                        + " all three local sources - Xj3D's BaseNavigationInfo, UOM 4.1 and the"
                        + " NIST jar's NavigationInfo class - say 'type', so 'kind' is nowhere in"
                        + " this table and a legal VRML97 file will be called wrong. LOD.level and"
                        + " Switch.choice survived only because Xj3D's fieldMap still lists them"
                        + " (see 'names a field also answers to'). Is there a fourth source - the"
                        + " ISO text, or a hand-written rename list someone will sign - for the rest?",
                "Default values come from UOM 4.1, which are X3D's defaults. Where VRML97 and X3D"
                        + " differ (NavigationInfo's navigation class list, TextureTransform's"
                        + " center, anything X3D re-specced) hover will quote the X3D number. Is"
                        + " that good enough for a hover, or does the table need VRML97 defaults?",
                "The child lists on SFNode/MFNode fields come from UOM's X3D abstract types"
                        + " (X3DChildNode, X3DGeometryNode, ...) expanded over the 56 nodes. One"
                        + " VRML97 correction is written into Join: X3D re-parented"
                        + " X3DGeometryNode straight off X3DChildNode after moving geometry inside"
                        + " Shape, which would leave every geometry node out of children, so the"
                        + " geometry members are added back to a child list. What still classifies"
                        + " as X3D rather than VRML97: MovieTexture and AudioClip are offered as"
                        + " direct children (VRML97 hangs an AudioClip on a TimeSensor), and 13"
                        + " abstract names match none of the 56 and are reported separately.",
                "Two of the 56 names come from Xj3D's own config rather than from VRML97:"
                        + " config/2.0/profiles.xml is Xj3D's renderer inventory, grouped by X3D"
                        + " component, and it lists WorldRoot - the root node Xj3D builds"
                        + " internally, which no scene file can name and which has no"
                        + " <ConcreteNode> in UOM at all - and LineSet, which sits beside"
                        + " IndexedLineSet under Render and is X3D's node (VRML97 line geometry is"
                        + " IndexedLineSet and PointSet). Should both stay out of node-name"
                        + " completion? If LineSet stays, is it offered as a synonym a user can"
                        + " write in a .wrl?");
    }
}
