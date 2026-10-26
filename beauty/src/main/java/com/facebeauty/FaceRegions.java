package com.facebeauty;

/** MediaPipe face-mesh landmark indices used by the effects ("right" = subject's right). */
final class FaceRegions {

    static final int MIN_LANDMARKS = 468;

    /** Silhouette, clockwise from the top of the forehead. */
    static final int[] FACE_OVAL = {
            10, 338, 297, 332, 284, 251, 389, 356, 454, 323, 361, 288, 397, 365, 379, 378, 400, 377,
            152, 148, 176, 149, 150, 136, 172, 58, 132, 93, 234, 127, 162, 21, 54, 103, 67, 109};

    static final int[] LIPS_OUTER = {
            61, 146, 91, 181, 84, 17, 314, 405, 321, 375, 291, 409, 270, 269, 267, 0, 37, 39, 40, 185};
    static final int[] LIPS_INNER = {
            78, 95, 88, 178, 87, 14, 317, 402, 318, 324, 308, 415, 310, 311, 312, 13, 82, 81, 80, 191};

    static final int[] RIGHT_EYE = {
            33, 7, 163, 144, 145, 153, 154, 155, 133, 173, 157, 158, 159, 160, 161, 246};
    static final int[] LEFT_EYE = {
            263, 249, 390, 373, 374, 380, 381, 382, 362, 398, 384, 385, 386, 387, 388, 466};

    static final int[] RIGHT_BROW_UPPER = {70, 63, 105, 66, 107};
    static final int[] RIGHT_BROW_LOWER = {46, 53, 52, 65, 55};
    static final int[] LEFT_BROW_UPPER = {300, 293, 334, 296, 336};
    static final int[] LEFT_BROW_LOWER = {276, 283, 282, 295, 285};

    static final int FOREHEAD = 10;
    static final int CHIN = 152;
    static final int NOSE_TIP = 1;
    static final int RIGHT_CHEEK = 234;
    static final int LEFT_CHEEK = 454;
    static final int RIGHT_EYE_OUTER = 33;
    static final int RIGHT_EYE_INNER = 133;
    static final int LEFT_EYE_INNER = 362;
    static final int LEFT_EYE_OUTER = 263;
    static final int RIGHT_NOSE_WING = 98;
    static final int LEFT_NOSE_WING = 327;
    static final int RIGHT_UPPER_LID = 159;
    static final int RIGHT_LOWER_LID = 145;
    static final int LEFT_UPPER_LID = 386;
    static final int LEFT_LOWER_LID = 374;
    static final int UPPER_LIP = 13;
    static final int LOWER_LIP = 14;
    static final int MOUTH_RIGHT = 61;
    static final int MOUTH_LEFT = 291;

    /** The refined (478-point) model adds the irises: centre, then 4 points on the edge. */
    static final int IRIS_LANDMARKS = 478;
    static final int[] IRIS_CENTERS = {468, 473};

    /** Jaw pairs (right, left) pushed inward for face slimming, with relative weights. */
    static final int[][] JAW_PAIRS = {{132, 361}, {172, 397}};
    static final float[] JAW_WEIGHTS = {0.6f, 1.0f};
    /** Chin sides pulled inward for a narrower chin. */
    static final int[] CHIN_SIDES = {176, 400};

    private FaceRegions() {
    }
}
