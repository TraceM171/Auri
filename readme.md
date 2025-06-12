# **AURI** — Automation Utility for Ransomware Intelligence

AURI (Automation Utility for Ransomware Intelligence) is an extensible tool that automates the rigorous evaluation of
security products against real-world ransomware threats in a controlled environment.

## ⚠️ Warning

**This project downloads and executes live malware samples.**  
Run it **only in an isolated and controlled environment**, such as a virtual machine with no network access.  
Accidental execution on the host system may result in irreversible damage.

## Features

- **Extensible**: Most of its functionality can be extended by the use of custom plugins and configurations.
- **Modular**: The evaluation pipeline is segmented into multiple stages, allowing for parallel execution and easy
  integration of new components.
- **Reproducible**: All the parameters are stored in a runbook file, which can be used to reproduce the evaluation at
  any time by sharing it with others.
- **Automated**: The tool can be run in a fully automated mode, where it will execute the evaluation pipeline without
  any user intervention and streams the results to a database.
- **CLI Frontend**: AURI provides a command-line interface for easy interaction and control of the evaluation process.

## Installation

AURI is a Kotlin/JVM desktop application, and it can be built using Gradle. To run AURI, you need to have the following
prerequisites installed:

- **Java 11 or higher**: Ensure you have a compatible JDK installed.
- **Gradle**: You can install Gradle from [the official website](https://gradle.org/install/).

To build and run AURI, follow these steps:

1. Clone the repository:
   ```bash
   git clone https://github.com/TraceM171/Auri
    ```

2. Navigate to the project directory:
    ```bash
    cd Auri
    ```

3. Build the project using Gradle:
    ```bash
    ./gradlew app-cli:installDist
    ```
4. After the build is complete, you can find the AURI CLI executable in the `app-cli/build/install/auri/bin` directory.
   The exact file extension will vary depending on your operating system (e.g., .bat for Windows, no extension for
   Unix-based systems).

## Usage

### Overview

The AURI pipeline has 3 main stages:

1. **Collection**: This stage collects ransomware samples from various sources, such as public repositories or custom
   feeds.
2. **Liveness**: In this stage, the collected samples are executed in a controlled environment to determine if they are
   active ransomware.
3. **Evaluation**: The final stage evaluates the security products against the active ransomware samples to determine
   their effectiveness.

### Configuration

To run any of these stages, you will need two things:

- A **runbook** file that defines the parameters for the pipeline.
- A **base directory** where all the files will be stored, including the database and the results of the evaluation. May
  be an empty directory if it is the first time you run the pipeline.

A sample runbook file is provided in the `runbooks` directory of the repository. You can use this file as a starting
point and modify it according to your needs. Most parameters have sensible defaults, so you can run the pipeline with
minimal configuration.

### Quickstart

To quickly start the evaluation pipeline, you can use the provided runbook file and a base directory. For example, to
run the **collection stage**, you can execute the following command:

```bash
  auri collection --runbook runbooks/sample-runbook.yml --base-directory /path/to/base/dir
```

Each stage can be started via subcommands in the CLI. For more information, run the following command:

```bash
  auri --help
```

Each subcommand has also its own help message, which can be accessed by running:

```bash
  auri <subcommand> --help
```

## Results

The results of the evaluation are stored in a database, which can be queried to retrieve information about the
evaluation process, such as the samples collected, the liveness results, and the evaluation results. The database can be
accessed using a database client of your choice. It is an SQLite database file located in the base directory specified
in the runbook, named `auri.db`.

### Exporting Results

To export the results of the evaluation to a file, you can archive the contents of the base directory alongside the
runbook file.
Ensure that any sensitive information is removed before sharing, especially in these locations:

- The `logs` directory, which contains logs of the evaluation process that may include sensitive information.
- In your `runbook`, redact any sensitive information such as API keys, credentials, or any other parameters that you
  may have used.
- Also, you can exclude the `cache` directory, which contains the downloaded samples and other temporary files that are
  not needed for the evaluation.

## Extending AURI

### Plugin Deployment

To use a custom plugin:

1. Implement the appropriate `@ExtensionPoint` interface.
2. Compile your plugin into a `.jar` file.
3. Place the `.jar` in the `extensions` directory within your base directory.

AURI will automatically load all `.jar` files from the `extensions` directory at runtime and make them available for use
in the pipeline.

### Reproducibility

Remember that if you use custom extensions, in order for other users to be able to reproduce your evaluation, you will
need to include the extensions in the exported artifact.