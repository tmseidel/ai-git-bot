package org.remus.giteabot.agent.packagemanager;

/**
 * The package manager {@link PackageManagerDetector} found in a checkout.
 *
 * @param manager        the manager the checkout declares
 * @param yarnVersionSpec the Yarn version the checkout pins ({@code "1"} for Yarn Classic,
 *                        {@code "4.1.1"} for a modern release); {@code null} when the checkout
 *                        names Yarn without naming a version
 * @param evidence       the file or field the decision came from, quoted back to the model in a
 *                       refusal so it can see why npm or yarn was chosen
 */
public record PackageManagerDetection(PackageManager manager, String yarnVersionSpec, String evidence) {

    static PackageManagerDetection npm(String evidence) {
        return new PackageManagerDetection(PackageManager.NPM, null, evidence);
    }

    static PackageManagerDetection yarn(String yarnVersionSpec, String evidence) {
        return new PackageManagerDetection(PackageManager.YARN, yarnVersionSpec, evidence);
    }

    static PackageManagerDetection unknown(String evidence) {
        return new PackageManagerDetection(PackageManager.UNKNOWN, null, evidence);
    }

    public boolean isYarn() {
        return manager == PackageManager.YARN;
    }
}
