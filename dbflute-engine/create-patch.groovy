import groovy.ant.AntBuilder

def branchName = "git rev-parse --abbrev-ref HEAD".execute().text.trim()
def ant = new AntBuilder()
ant.delete(file:"target/patch/${branchName}.jar")
ant.jar(destfile: "target/patch/${branchName}.jar") {
    fileset(dir: "target/classes", includes: "**/DfTableExtractor*.class")
    fileset(dir: "src/main/java", includes: "**/DfTableExtractor*.java")
}
