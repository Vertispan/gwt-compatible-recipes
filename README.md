# gwt-compatible-recipes

OpenRewrite recipes handy for repackaging Java libraries for use with GWT/J2CL. This is admittedly not what
OpenRewrite is intended to be used for, but it does offer some useful tools to rewrite classes the developer
does not control and still retain the original comments and formatting.

These may be useful either for producing supersource, or for creating a replacement library that can be compiled.

Very few of these are actually GWT- or J2CL-specific, but instead offer tools to more drastically rewrite Java
projects than OpenRewrite is really intended for.

This project isn't intended to be used like a library, and doesn't use SemVer, but just a major.minor version
to indicate that a release has major changes vs minor tweaks.