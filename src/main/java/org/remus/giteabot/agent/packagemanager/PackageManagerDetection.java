package org.remus.giteabot.agent.packagemanager;

/**
 * The package manager {@link PackageManagerDetector} found in a checkout.
 *
 * @param manager                 the manager the checkout declares
 * @param yarnVersionSpec         the Yarn version the checkout pins ({@code "1"} for Yarn Classic,
 *                                {@code "4.1.1"} for a modern release); {@code null} when the
 *                                checkout names Yarn without naming a version
 * @param yarnVersionFromManifest {@code true} when {@code yarnVersionSpec} came from
 *                                {@code package.json}'s {@code packageManager} field. Such a pin is
 *                                left to Corepack rather than passed as an explicit {@code yarn@…}:
 *                                Corepack then verifies the {@code +sha512…} integrity hash the
 *                                repository opted into, which an explicit version would bypass.
 * @param yarnPath                the release the checkout vendors through {@code yarnPath}
 *                                ({@code .yarn/releases/yarn-x.y.z.cjs}), workspace-relative and
 *                                verified to stay inside the workspace; {@code null} when absent
 * @param evidence                the file or field the decision came from, quoted back to the model
 *                                in a refusal so it can see why npm or yarn was chosen
 */
public record PackageManagerDetection(PackageManager manager, String yarnVersionSpec,
                                      boolean yarnVersionFromManifest, String yarnPath, String evidence) {

    static PackageManagerDetection npm(String evidence) {
        return new PackageManagerDetection(PackageManager.NPM, null, false, null, evidence);
    }

    static PackageManagerDetection yarnFromManifest(String yarnVersionSpec, String evidence) {
        return new PackageManagerDetection(PackageManager.YARN, yarnVersionSpec, true, null, evidence);
    }

    static PackageManagerDetection yarnFromLockfile(String yarnVersionSpec, String yarnPath,
                                                    String evidence) {
        return new PackageManagerDetection(PackageManager.YARN, yarnVersionSpec, false, yarnPath,
                evidence);
    }

    static PackageManagerDetection unknown(String evidence) {
        return new PackageManagerDetection(PackageManager.UNKNOWN, null, false, null, evidence);
    }

    public boolean isYarn() {
        return manager == PackageManager.YARN;
    }
}
