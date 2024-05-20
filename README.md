# Table of Contents

- [General Introduction](#general-introduction)
- [Contents of the Replication Package](#contents-of-the-replication-package)
- [Requirements](#requirements)
- [Data](#data)
- [How to Replicate the Evaluation](#how-to-replicate-the-evaluation)

# General Introduction

This is the replication package for TOSEM2024 submission, containing both tool and data that are requested by the replication. It also provides detailed instructions to replicate the evaluation.

# Contents of the Replication Package

/data: Input of the evaluation as well as the outputs of the evaluation.

/tool: The implementation of the evaluated approaches (including the proposed approach and the baseline approach).

# Requirements

- Java 17.0.5 or newer
- Apache Maven 3.8.1 or newer

# Data

### 1. Scripts

Two scripts (**clone_projects.bat** and **clone_projects.sh**) that can downlow dataset from open-source projects, and the resulting dataset should be taken as the input of the evaluation.

### 2. Refactoring Discovery

All results reported by the proposed approach and the baseline approach as well as the labels manually validated by the refactoring experts, are available at the following links:

* [refactoring detection](data/refactoring%20detection/)

### 3. Entity Matching

All results reported by the proposed approach and the baseline approach as well as the labels manually validated by the experienced developers, are available at the following links:

* [entity matching](data/entity%20matching/)

# How to Replicate the Evaluation?

## IntelliJ IDEA

1. **Clone replicate package to your local file system**

   `git clone https://github.com/bitselab/ReExtractorPlus.git`

   <img src="./data/figures/clone_repository.png" alt="clone repository" width="60%" />

2. **Import project**

   Go to *File* -> *Open...*

   Browse to the **tool** directory of project ReExtractorPlus

   Click *OK*

   The project will be built automatically.

   <img src="./data/figures/import_project.png" alt="import project" width="80%;" />

3. **Clone open-source project repositories (dataset)**

   `double-click clone_projects.bat` &emsp;&nbsp;(on windows environment)

   `./clone_projects.sh` &emsp;&emsp;&emsp;&emsp;&emsp;&emsp;&emsp;(on linux environment)

   <img src="./data/figures/clone_dataset.png" alt="clone dataset" width="60%;" />

4. **Run the refactoring discovery experiment**

   Set `datasetPath` to the directory where the cloned dataset is located (line 18 in org.reextractorplus.experiment.RefactoringDetectionExperimentStarter.java)

   From the Project tab of IDEA navigate to `org.reextractorplus.experiment.RefactoringDetectionExperimentStarter`

   Right-click on the file and select *Run RefactoringDetectionExperimentStarter.main()*

   All results of refactoring discovery will be output to the console

   <img src="./data/figures/run_refactoring_detection_experiment.png" alt="run refactoring detection experiment" width="80%;" />

   All results of refactoring discovery will be stored in the local file system in JSON format:

   `/data/refactoring discovery/<project>.json` 

   <img src="./data/figures/refactoring_detection_experiment_results.png" alt="refactoring detection experiment results" width="80%;" />
